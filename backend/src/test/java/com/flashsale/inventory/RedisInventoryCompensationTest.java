package com.flashsale.inventory;

import com.flashsale.domain.Order;
import com.flashsale.domain.Product;
import com.flashsale.domain.ProductStatus;
import com.flashsale.order.OrderService;
import com.flashsale.order.dto.CreateOrderCommand;
import com.flashsale.repository.OrderRepository;
import com.flashsale.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * The compensation guarantee: if order creation fails AFTER the Redis-and-DB
 * reservation succeeded, both layers must be restored.
 *
 * Without this, a transient DB hiccup during {@code orderRepository.save}
 * leaves a permanent ghost reservation — DB says "−1 stock used" and Redis
 * says "−1 stock used" with no order to back it. The buyer pool shrinks every
 * time something goes wrong.
 *
 * We force the save to throw via @SpyBean and verify both layers are back
 * where they started.
 */
@SpringBootTest
@Testcontainers
class RedisInventoryCompensationTest {

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
    @Autowired OrderService orderService;
    @Autowired ProductRepository productRepository;
    @Autowired StringRedisTemplate redisTemplate;

    // Spy so a single test method can force save() to throw without disturbing
    // other tests in the class.
    @SpyBean OrderRepository orderRepository;

    @Test
    void orderCreateFailureRestoresBothRedisAndPostgres() {
        // Confirm Redis throttle is actually live (not fallback mode).
        assertThat(((RedisInventoryService) inventoryService).isRedisActive()).isTrue();

        final int initialStock = 10;
        Product product = newProduct(initialStock);
        product = productRepository.saveAndFlush(product);
        Long productId = product.getId();
        inventoryService.loadProduct(productId);

        // Sanity baseline — both layers at 10.
        assertThat(productRepository.findById(productId).orElseThrow().getAvailableStock())
                .isEqualTo(initialStock);
        assertThat(redisStock(productId)).isEqualTo(String.valueOf(initialStock));

        // Force the order persist to blow up AFTER the reservation succeeded.
        // OrderService.createOrder catches RuntimeException and calls
        // inventoryService.release — which must restore both layers.
        doThrow(new RuntimeException("simulated DB hiccup on order save"))
                .when(orderRepository).save(any(Order.class));

        assertThatThrownBy(() -> orderService.createOrder(new CreateOrderCommand(
                productId, 1, "Buyer", "buyer@example.com", "0900000000", "1 Main St")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("simulated DB hiccup");

        // The whole point: both layers must be back to 10. If either has
        // drifted, the compensation is broken.
        Product after = productRepository.findById(productId).orElseThrow();
        assertThat(after.getAvailableStock())
                .as("Postgres available_stock restored after order-save failure")
                .isEqualTo(initialStock);
        assertThat(redisStock(productId))
                .as("Redis counter restored after order-save failure")
                .isEqualTo(String.valueOf(initialStock));
    }

    /**
     * Multi-shot variant of the compensation test. The key question is whether
     * compensation overshoots — i.e. whether each failed createOrder attempt
     * leaves the stock 1 higher than it should be.
     *
     * Why this would happen: if {@link DatabaseInventoryService#tryReserve}
     * were ever changed from REQUIRES_NEW to plain REQUIRED, the stock
     * decrement would join the outer createOrder transaction. On save failure,
     * outer rollback restores stock automatically, AND the catch's
     * inventoryService.release runs +1, so each failure inflates stock by 1.
     * Five failures from a stock of 10 → DB sits at 15, Redis at 15.
     *
     * The REQUIRES_NEW on tryReserve is what keeps compensation correct:
     * the decrement is independently committed, so rollback doesn't touch
     * it, so release's +1 is the ONLY thing restoring stock. This test pins
     * the invariant — if it fails, the propagation was changed somewhere
     * and overshoot is real.
     */
    @Test
    void multipleConsecutiveFailuresKeepStockExactlyAtOriginal() {
        assertThat(((RedisInventoryService) inventoryService).isRedisActive()).isTrue();

        final int initialStock = 10;
        Product product = newProduct(initialStock);
        product = productRepository.saveAndFlush(product);
        Long productId = product.getId();
        inventoryService.loadProduct(productId);

        // Baseline — both layers at 10.
        assertThat(productRepository.findById(productId).orElseThrow().getAvailableStock())
                .isEqualTo(initialStock);
        assertThat(redisStock(productId)).isEqualTo(String.valueOf(initialStock));

        // Every order save throws — five attempts, all fail at the same point
        // (after tryReserve committed but before the order row persists).
        doThrow(new RuntimeException("save fails on every attempt"))
                .when(orderRepository).save(any(Order.class));

        final int attempts = 5;
        for (int i = 0; i < attempts; i++) {
            assertThatThrownBy(() -> orderService.createOrder(new CreateOrderCommand(
                    productId, 1, "Buyer", "buyer@example.com", "0900000000", "1 Main St")))
                    .isInstanceOf(RuntimeException.class);
        }

        // The strict invariant: after N failures both layers sit EXACTLY at
        // the original, not at original+N (compensation overshoots) and not
        // at original-N (compensation missed).
        Product after = productRepository.findById(productId).orElseThrow();
        assertThat(after.getAvailableStock())
                .as("DB stock must equal initial after %d failed createOrders — not inflated, not deflated", attempts)
                .isEqualTo(initialStock);
        assertThat(redisStock(productId))
                .as("Redis stock must equal initial after %d failed createOrders", attempts)
                .isEqualTo(String.valueOf(initialStock));
    }

    @Test
    void releaseAfterSuccessfulReservationRestoresBothLayers() {
        // Direct test of the release path itself, independent of OrderService.
        assertThat(((RedisInventoryService) inventoryService).isRedisActive()).isTrue();

        Product product = newProduct(5);
        product = productRepository.saveAndFlush(product);
        Long productId = product.getId();
        inventoryService.loadProduct(productId);

        assertThat(inventoryService.tryReserve(productId, 3)).isTrue();
        assertThat(redisStock(productId)).isEqualTo("2");
        assertThat(productRepository.findById(productId).orElseThrow().getAvailableStock()).isEqualTo(2);

        inventoryService.release(productId, 3);
        assertThat(redisStock(productId))
                .as("Redis counter must return to original after release")
                .isEqualTo("5");
        assertThat(productRepository.findById(productId).orElseThrow().getAvailableStock())
                .as("Postgres available_stock must return to original after release")
                .isEqualTo(5);
    }

    private String redisStock(Long productId) {
        return redisTemplate.opsForValue().get(RedisInventoryService.STOCK_KEY_PREFIX + productId);
    }

    private static Product newProduct(int stock) {
        Product p = new Product();
        p.setName("Redis Compensation Test");
        p.setPrice(new BigDecimal("1000.00"));
        p.setTotalStock(stock);
        p.setAvailableStock(stock);
        p.setDropStartsAt(Instant.now().minusSeconds(60));
        p.setStatus(ProductStatus.ACTIVE);
        return p;
    }
}
