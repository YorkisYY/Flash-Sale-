package com.flashsale.inventory;

import com.flashsale.domain.Product;
import com.flashsale.domain.ProductStatus;
import com.flashsale.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No-oversell gate, Redis-throttle edition. Same shape as
 * {@link InventoryConcurrencyTest} but the Redis bean IS present this time —
 * so the Lua check-and-DECR is exercised and the DB UPDATE sits behind it as
 * final defense. Both layers must end at zero stock and exactly stock-many
 * reservations must succeed.
 *
 * If this test ever passes but {@link InventoryConcurrencyTest} fails (or
 * vice versa), the two layers have diverged and the compensation in
 * {@link RedisInventoryService} needs to be revisited.
 */
@SpringBootTest
@Testcontainers
class RedisInventoryConcurrencyTest {

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

    @DynamicPropertySource
    static void wireRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired InventoryService inventoryService;
    @Autowired ProductRepository productRepository;
    @Autowired StringRedisTemplate redisTemplate;

    @Test
    void exactlyStockSucceedsThroughRedisAndDb() throws Exception {
        // Sanity: with Redis container running, the Redis throttle must
        // actually be active (not silently in DB-only fallback mode).
        assertThat(inventoryService).isInstanceOf(RedisInventoryService.class);
        assertThat(((RedisInventoryService) inventoryService).isRedisActive())
                .as("Redis throttle must be active, not in DB-only fallback")
                .isTrue();

        final int stock = 100;
        final int attempts = 500;

        Product product = newProduct(stock);
        product = productRepository.saveAndFlush(product);
        final Long productId = product.getId();
        // The Redis bootstrap on ApplicationReadyEvent may already have run
        // before this product existed, so push it explicitly.
        inventoryService.loadProduct(productId);

        ExecutorService pool = Executors.newFixedThreadPool(64);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(attempts);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures  = new AtomicInteger();

        for (int i = 0; i < attempts; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (inventoryService.tryReserve(productId, 1)) {
                        successes.incrementAndGet();
                    } else {
                        failures.incrementAndGet();
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

        assertThat(finished).as("all attempts completed within 60s").isTrue();
        assertThat(successes.get())
                .as("exactly stock-many reservations succeed even with Redis in front of DB")
                .isEqualTo(stock);
        assertThat(failures.get())
                .as("remainder are throttled (Redis-sold-out or DB-rejected)")
                .isEqualTo(attempts - stock);

        // Both layers ended at zero — they're in sync.
        Product reloaded = productRepository.findById(productId).orElseThrow();
        assertThat(reloaded.getAvailableStock())
                .as("Postgres available_stock ends at 0")
                .isZero();

        String redisStock = redisTemplate.opsForValue().get(RedisInventoryService.STOCK_KEY_PREFIX + productId);
        assertThat(redisStock)
                .as("Redis counter ends at 0 (Lua DECR'd it down)")
                .isEqualTo("0");
    }

    private static Product newProduct(int stock) {
        Product p = new Product();
        p.setName("Redis Test Drop");
        p.setPrice(new BigDecimal("1000.00"));
        p.setTotalStock(stock);
        p.setAvailableStock(stock);
        p.setDropStartsAt(Instant.now().minusSeconds(60));
        p.setStatus(ProductStatus.ACTIVE);
        return p;
    }
}
