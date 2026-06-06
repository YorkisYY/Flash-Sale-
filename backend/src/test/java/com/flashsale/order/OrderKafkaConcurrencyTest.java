package com.flashsale.order;

import com.flashsale.domain.Product;
import com.flashsale.domain.ProductStatus;
import com.flashsale.inventory.InventoryService;
import com.flashsale.inventory.RedisInventoryService;
import com.flashsale.repository.OrderRepository;
import com.flashsale.repository.ProductRepository;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No-oversell regression under the new async flow.
 *
 * <p>Fires N concurrent requests through the same code path the controller
 * uses (Redis Lua DECR + ingestion publish), awaits consumer drain via
 * Awaitility, then asserts the absolute invariant: number of CREATED orders
 * equals initial stock, both stock counters end at 0, and no over-allotment
 * leaked through.
 *
 * <p>Bypasses MockMvc deliberately — true parallel requests through MockMvc
 * are awkward (one DispatcherServlet per call, plus rate limiter would
 * burn through localhost's bucket immediately). The atomicity property
 * being tested is at the inventory layer, not the HTTP layer; the HTTP
 * layer is covered by OrderKafkaPipelineTest.
 */
@SpringBootTest
@Testcontainers
class OrderKafkaConcurrencyTest {

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("flashsale_test")
            .withUsername("flashsale")
            .withPassword("flashsale");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Container
    @SuppressWarnings("resource")
    static ConfluentKafkaContainer kafka = new ConfluentKafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("flashsale.order.expiry-scan-interval-ms",   () -> "600000");
        registry.add("flashsale.inventory.reconcile-interval-ms", () -> "600000");
    }

    @Autowired InventoryService inventoryService;
    @Autowired OrderIngestionService ingestionService;
    @Autowired ProductRepository productRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired StringRedisTemplate redisTemplate;

    @Test
    void concurrentPurchasesDrainToExactlyStockMany() throws Exception {
        final int initialStock = 100;
        final int parallel    = 200;

        Product p = newProduct(initialStock);
        p = productRepository.saveAndFlush(p);
        final Long productId = p.getId();
        inventoryService.loadProduct(productId);

        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(parallel);
        AtomicInteger admitted = new AtomicInteger(); // Redis DECR succeeded + event published
        AtomicInteger throttled = new AtomicInteger(); // Redis DECR rejected (sold out)
        AtomicInteger publishFailed = new AtomicInteger();

        for (int i = 0; i < parallel; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (!inventoryService.tryReserveRedisOnly(productId, 1)) {
                        throttled.incrementAndGet();
                        return;
                    }
                    try {
                        ingestionService.publishRequest(
                                productId, 1,
                                "Buyer", "buyer@example.com", "0900000000", "1 Main St", "ECPAY");
                        admitted.incrementAndGet();
                    } catch (RuntimeException publishErr) {
                        // compensate Redis (same as the controller does)
                        inventoryService.releaseRedisOnly(productId, 1);
                        publishFailed.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        boolean finished = done.await(60, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertThat(finished).as("all parallel ingestion calls completed").isTrue();

        // Redis Lua DECR is atomic — exactly stock-many requests get past
        // the throttle. The remainder hit a sold-out 0.
        assertThat(admitted.get()).as("exactly stock-many admitted by Redis throttle").isEqualTo(initialStock);
        assertThat(throttled.get()).as("remainder throttled as sold-out").isEqualTo(parallel - initialStock);
        assertThat(publishFailed.get()).as("no publish failures").isZero();

        // Drain — consumer needs to write all admitted orders to Postgres.
        // Up to 60s on first-test cold partition assignment.
        Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    long persisted = orderRepository.findAll().stream()
                            .filter(o -> productId.equals(o.getProductId())).count();
                    assertThat(persisted).as("consumer drained all admitted events").isEqualTo(initialStock);
                });

        // The hard invariants — these are the no-oversell proof.
        assertThat(orderRepository.findAll().stream()
                .filter(o -> productId.equals(o.getProductId())).count())
                .as("exactly stock-many orders persisted; zero oversell")
                .isEqualTo(initialStock);
        assertThat(productRepository.findById(productId).orElseThrow().getAvailableStock())
                .as("DB stock drained to zero, never negative")
                .isZero();
        assertThat(redisStock(productId))
                .as("Redis stock drained to zero, in sync with DB")
                .isEqualTo("0");
    }

    private String redisStock(Long productId) {
        return redisTemplate.opsForValue().get(RedisInventoryService.STOCK_KEY_PREFIX + productId);
    }

    private static Product newProduct(int stock) {
        Product p = new Product();
        p.setName("Kafka Concurrency Test");
        p.setPrice(new BigDecimal("1000.00"));
        p.setTotalStock(stock);
        p.setAvailableStock(stock);
        p.setDropStartsAt(Instant.now().minusSeconds(60));
        p.setStatus(ProductStatus.ACTIVE);
        return p;
    }
}
