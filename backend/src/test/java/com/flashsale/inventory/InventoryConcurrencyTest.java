package com.flashsale.inventory;

import com.flashsale.domain.Product;
import com.flashsale.domain.ProductStatus;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The "no oversell" gate. This test must be green before any other backend
 * work proceeds.
 *
 * Setup: a real Postgres via Testcontainers (not H2, not mocks — Postgres's
 * row-level locking behavior is what we're actually testing).
 *
 * Scenario: a product with stock M, fired at by N concurrent reservation
 * attempts (N > M). Assertions:
 *   - exactly M reservations succeed
 *   - available_stock ends at exactly 0
 *   - available_stock never goes negative at any point
 */
@SpringBootTest
@Testcontainers
class InventoryConcurrencyTest {

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("flashsale_test")
            .withUsername("flashsale")
            .withPassword("flashsale");

    @DynamicPropertySource
    static void disableRedisAutoconfig(DynamicPropertyRegistry registry) {
        // Tests don't need Redis; exclude its starter so missing host doesn't break the context.
        registry.add("spring.autoconfigure.exclude",
                () -> "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration");
    }

    @Autowired private InventoryService inventoryService;
    @Autowired private ProductRepository productRepository;

    @Test
    void exactlyStockSucceedsUnderConcurrentLoad() throws Exception {
        final int stock = 50;
        final int attempts = 500;

        Product product = newProduct(stock);
        product = productRepository.saveAndFlush(product);
        final Long productId = product.getId();

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
        assertThat(successes.get()).as("exactly stock-many reservations succeed").isEqualTo(stock);
        assertThat(failures.get()).as("remainder fail as sold out").isEqualTo(attempts - stock);

        Product reloaded = productRepository.findById(productId).orElseThrow();
        assertThat(reloaded.getAvailableStock())
                .as("available_stock never went negative and ended at exactly 0")
                .isZero();
    }

    @Test
    void releaseReturnsStock() {
        Product product = newProduct(10);
        product = productRepository.saveAndFlush(product);
        Long productId = product.getId();

        assertThat(inventoryService.tryReserve(productId, 4)).isTrue();
        assertThat(productRepository.findById(productId).orElseThrow().getAvailableStock()).isEqualTo(6);

        inventoryService.release(productId, 4);
        assertThat(productRepository.findById(productId).orElseThrow().getAvailableStock()).isEqualTo(10);
    }

    @Test
    void reservationFailsWhenInsufficientStock() {
        Product product = newProduct(3);
        product = productRepository.saveAndFlush(product);
        Long productId = product.getId();

        assertThat(inventoryService.tryReserve(productId, 5)).isFalse();
        assertThat(productRepository.findById(productId).orElseThrow().getAvailableStock()).isEqualTo(3);
    }

    private static Product newProduct(int stock) {
        Product p = new Product();
        p.setName("Test Drop");
        p.setPrice(new BigDecimal("1000.00"));
        p.setTotalStock(stock);
        p.setAvailableStock(stock);
        p.setDropStartsAt(Instant.now().minusSeconds(60));
        p.setStatus(ProductStatus.ACTIVE);
        return p;
    }
}
