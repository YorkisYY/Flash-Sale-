package com.flashsale.order;

import com.flashsale.domain.Order;
import com.flashsale.domain.OrderStatus;
import com.flashsale.domain.Product;
import com.flashsale.domain.ProductStatus;
import com.flashsale.repository.OrderRepository;
import com.flashsale.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Correctness-layer proof for {@link OrderExpiryJob}, with NO Redis present.
 *
 * <p>{@code RedisAutoConfiguration} is excluded, so ShedLock falls back to its
 * no-op always-grant lock provider — exactly like a single-node, DB-only
 * deployment. That makes this test prove two things at once:
 * <ol>
 *   <li>The scheduled job still RUNS with no Redis / no real lock (the no-op
 *       lock grants every acquisition), and</li>
 *   <li>the atomic conditional status transition in {@code sweep()} releases
 *       stock EXACTLY ONCE even when many workers sweep the SAME expired order
 *       concurrently — independent of any distributed lock.</li>
 * </ol>
 *
 * If this test passes but the lock test doesn't, the lock is broken but
 * correctness still holds (acceptable). If THIS test fails, the correctness
 * layer itself is broken — which is the dangerous regression.
 */
@SpringBootTest
@Testcontainers
class OrderExpiryGuardTest {

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("flashsale_test")
            .withUsername("flashsale")
            .withPassword("flashsale");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        // No Redis, no Kafka — DB-only mode. ShedLock uses its no-op provider.
        registry.add("spring.autoconfigure.exclude",
                () -> "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration");
        registry.add("spring.kafka.bootstrap-servers", () -> "");
        // Push the real scheduler far out AND suppress the startup auto-fire;
        // tests fire sweep() explicitly.
        registry.add("flashsale.order.expiry-scan-interval-ms",        () -> "600000");
        registry.add("flashsale.inventory.reconcile-interval-ms",      () -> "600000");
        registry.add("flashsale.order.expiry-initial-delay-ms",        () -> "600000");
        registry.add("flashsale.inventory.reconcile-initial-delay-ms", () -> "600000");
    }

    @Autowired OrderExpiryJob   orderExpiryJob;
    @Autowired ProductRepository productRepository;
    @Autowired OrderRepository  orderRepository;

    /** Baseline: the job runs and releases stock with no Redis at all. */
    @Test
    void sweepRunsAndReleasesWithoutRedis() {
        Product product = productRepository.saveAndFlush(newProduct(0, 5));
        Long productId = product.getId();
        Order order = orderRepository.saveAndFlush(expiredOrder(productId, 3));

        orderExpiryJob.sweep();

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .as("expired order moved to EXPIRED with no Redis present")
                .isEqualTo(OrderStatus.EXPIRED);
        assertThat(productRepository.findById(productId).orElseThrow().getAvailableStock())
                .as("3 reserved units released back exactly once")
                .isEqualTo(3);
    }

    /**
     * The guard. Many workers sweep the SAME expired order at once. The atomic
     * {@code transitionStatus} lets exactly one worker move it out of CREATED;
     * the rest match zero rows and skip the release. Stock returns exactly
     * once — no double-release — with no lock involved.
     */
    @Test
    void concurrentSweepsReleaseTheSameOrderExactlyOnce() throws Exception {
        final int workers = 8;

        // Product with 0 available (its single unit is notionally reserved).
        Product product = productRepository.saveAndFlush(newProduct(0, 1));
        Long productId = product.getId();
        orderRepository.saveAndFlush(expiredOrder(productId, 1));

        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(workers);
        for (int w = 0; w < workers; w++) {
            pool.submit(() -> {
                try {
                    start.await();
                    orderExpiryJob.sweep();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).as("all sweeps finished").isTrue();
        pool.shutdownNow();

        assertThat(productRepository.findById(productId).orElseThrow().getAvailableStock())
                .as("no double-release: the 1 unit returns exactly once despite %d racing sweeps", workers)
                .isEqualTo(1);
        assertThat(orderRepository.findAll())
                .as("the order ended EXPIRED")
                .allMatch(o -> o.getStatus() == OrderStatus.EXPIRED);
    }

    private static Product newProduct(int available, int total) {
        Product p = new Product();
        p.setName("Expiry Guard Test");
        p.setPrice(new BigDecimal("1000.00"));
        p.setTotalStock(total);
        p.setAvailableStock(available);
        p.setDropStartsAt(Instant.now().minusSeconds(60));
        p.setStatus(ProductStatus.ACTIVE);
        return p;
    }

    private static Order expiredOrder(Long productId, int qty) {
        Order o = new Order();
        o.setProductId(productId);
        o.setQuantity(qty);
        o.setBuyerName("Buyer");
        o.setBuyerEmail("buyer@example.com");
        o.setBuyerPhone("0900000000");
        o.setShippingAddress("1 Main St");
        o.setAmount(new BigDecimal("1000.00").multiply(BigDecimal.valueOf(qty)));
        o.setStatus(OrderStatus.CREATED);
        o.setPaymentIdempotencyKey(UUID.randomUUID().toString().replace("-", ""));
        o.setExpiresAt(Instant.now().minusSeconds(30));
        return o;
    }
}
