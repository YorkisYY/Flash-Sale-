package com.flashsale.inventory;

import com.flashsale.domain.Order;
import com.flashsale.domain.Product;
import com.flashsale.domain.ProductStatus;
import com.flashsale.order.OrderExpiryJob;
import com.flashsale.order.OrderService;
import com.flashsale.order.dto.CreateOrderCommand;
import com.flashsale.repository.OrderRepository;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two-store consistency invariant —
 *   {@code redis["stock:" + productId] == product.available_stock}
 * — proved across the three lifecycle paths that release reserved stock,
 * plus the reconciliation safety net that catches drift.
 *
 * Each test makes the stronger assertion than the audit's existing
 * compensation tests: not just "both counters are at the right value", but
 * "a fresh buyer can actually consume the released unit." That closes the
 * abandon-then-rebuy gap (the audit found this scenario uncovered).
 *
 * The schedulers (expiry sweeper, reconcile loop) are pushed far out via
 * {@link DynamicPropertySource} so they don't fire mid-test and contaminate
 * state — tests invoke {@code sweep()} / {@code reconcile()} explicitly.
 */
@SpringBootTest
@Testcontainers
class RedisInventoryReconciliationTest {

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
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        // Push scheduled tasks 10 minutes out so they don't tick during the
        // test window. Each test invokes the action it cares about directly.
        registry.add("flashsale.order.expiry-scan-interval-ms",        () -> "600000");
        registry.add("flashsale.inventory.reconcile-interval-ms",      () -> "600000");
        registry.add("spring.autoconfigure.exclude",
                () -> "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration");
        registry.add("spring.kafka.bootstrap-servers", () -> "");
    }

    @Autowired InventoryService    inventoryService;
    @Autowired RedisInventoryService redisService;          // direct ref for reconcile()
    @Autowired OrderService        orderService;
    @Autowired OrderExpiryJob      orderExpiryJob;
    @Autowired ProductRepository   productRepository;
    @Autowired OrderRepository     orderRepository;
    @Autowired StringRedisTemplate redisTemplate;

    /**
     * Abandon-then-rebuy via expiry sweeper. The audit flagged this lifecycle
     * as uncovered. After {@link OrderExpiryJob#sweep} flips a CREATED order
     * past expires_at into EXPIRED, BOTH layers must show stock back at
     * original, AND a fresh buyer must succeed (proves the buyer pool is
     * actually re-bookable, not just that the counters agree).
     */
    @Test
    void expiryReleasesStockAndAllowsRebuy() {
        assertThat(((RedisInventoryService) inventoryService).isRedisActive()).isTrue();

        Product product = newProduct(1);
        product = productRepository.saveAndFlush(product);
        Long productId = product.getId();
        inventoryService.loadProduct(productId);

        // First buyer reserves the only unit
        Order first = orderService.createOrder(buyCmd(productId, "First"));
        assertThat(productRepository.findById(productId).orElseThrow().getAvailableStock()).isZero();
        assertThat(redisStock(productId)).isEqualTo("0");

        // Time-travel: rewrite expires_at into the past, save back
        Order toExpire = orderRepository.findById(first.getId()).orElseThrow();
        toExpire.setExpiresAt(Instant.now().minusSeconds(10));
        orderRepository.saveAndFlush(toExpire);

        // Manually fire the sweeper (its scheduler is pushed to 10 min)
        orderExpiryJob.sweep();

        // BOTH layers back to 1
        assertThat(productRepository.findById(productId).orElseThrow().getAvailableStock())
                .as("DB available_stock must return to 1 after expiry").isEqualTo(1);
        assertThat(redisStock(productId))
                .as("Redis counter must return to 1 after expiry").isEqualTo("1");

        // The proof that closes the gap: a fresh buyer can claim the released unit
        Order rebuy = orderService.createOrder(buyCmd(productId, "Second"));
        assertThat(rebuy).isNotNull();
        assertThat(productRepository.findById(productId).orElseThrow().getAvailableStock())
                .as("Rebuy must drain DB back to 0").isZero();
        assertThat(redisStock(productId))
                .as("Rebuy must drain Redis back to 0").isEqualTo("0");
    }

    /**
     * Same shape as the expiry case but via {@link OrderService#cancel}.
     * Buyer cancels a CREATED order; BOTH layers must restore; a fresh
     * buyer succeeds.
     */
    @Test
    void cancellationReleasesStockAndAllowsRebuy() {
        assertThat(((RedisInventoryService) inventoryService).isRedisActive()).isTrue();

        Product product = newProduct(1);
        product = productRepository.saveAndFlush(product);
        Long productId = product.getId();
        inventoryService.loadProduct(productId);

        Order first = orderService.createOrder(buyCmd(productId, "First"));
        assertThat(redisStock(productId)).isEqualTo("0");

        orderService.cancel(first.getId());

        assertThat(productRepository.findById(productId).orElseThrow().getAvailableStock())
                .as("DB available_stock must return to 1 after cancellation").isEqualTo(1);
        assertThat(redisStock(productId))
                .as("Redis counter must return to 1 after cancellation").isEqualTo("1");

        Order rebuy = orderService.createOrder(buyCmd(productId, "Second"));
        assertThat(rebuy).isNotNull();
        assertThat(redisStock(productId)).isEqualTo("0");
    }

    /**
     * The reconciliation safety net. Corrupt Redis to a value below DB
     * truth (simulates a swallowed INCR failure during release, or a Redis
     * restart that lost state), invoke {@code reconcile()}, and assert
     * Redis is repaired back to the DB invariant.
     *
     * The "lower than DB truth" direction is the dangerous one — it's what
     * causes silent undersell (the buyer pool shrinks while DB has units).
     */
    @Test
    void reconcileRepairsCorruptedRedis() {
        assertThat(((RedisInventoryService) inventoryService).isRedisActive()).isTrue();

        Product product = newProduct(10);
        product = productRepository.saveAndFlush(product);
        Long productId = product.getId();
        inventoryService.loadProduct(productId);
        assertThat(redisStock(productId))
                .as("baseline — both layers at 10").isEqualTo("10");

        // Simulate drift: stomp the Redis key with a value below DB truth.
        // This is the same end-state as G1 (INCR failure during release)
        // or G3 (Redis restart while backend stayed up).
        redisTemplate.opsForValue().set(RedisInventoryService.STOCK_KEY_PREFIX + productId, "3");
        assertThat(redisStock(productId)).isEqualTo("3");

        // Run the reconciliation pass
        redisService.reconcile();

        // Redis must now match DB truth
        assertThat(redisStock(productId))
                .as("reconcile() must restore Redis to DB available_stock").isEqualTo("10");
        assertThat(productRepository.findById(productId).orElseThrow().getAvailableStock())
                .as("DB available_stock untouched by reconcile").isEqualTo(10);

        // And the repaired counter is consumable — a fresh buyer can reserve
        Order buyer = orderService.createOrder(buyCmd(productId, "Buyer After Reconcile"));
        assertThat(buyer).isNotNull();
        assertThat(redisStock(productId)).isEqualTo("9");
    }

    private String redisStock(Long productId) {
        return redisTemplate.opsForValue().get(RedisInventoryService.STOCK_KEY_PREFIX + productId);
    }

    private static Product newProduct(int stock) {
        Product p = new Product();
        p.setName("Reconciliation Test");
        p.setPrice(new BigDecimal("1000.00"));
        p.setTotalStock(stock);
        p.setAvailableStock(stock);
        p.setDropStartsAt(Instant.now().minusSeconds(60));
        p.setStatus(ProductStatus.ACTIVE);
        return p;
    }

    private static CreateOrderCommand buyCmd(Long productId, String name) {
        return new CreateOrderCommand(
                productId, 1,
                name, name.toLowerCase().replace(" ", "") + "@example.com",
                "0900000000", "1 Main St");
    }
}
