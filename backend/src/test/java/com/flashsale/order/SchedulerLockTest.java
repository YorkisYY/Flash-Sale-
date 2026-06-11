package com.flashsale.order;

import com.flashsale.domain.Order;
import com.flashsale.domain.OrderStatus;
import com.flashsale.domain.Product;
import com.flashsale.domain.ProductStatus;
import com.flashsale.inventory.InventoryService;
import com.flashsale.inventory.RedisInventoryService;
import com.flashsale.repository.OrderRepository;
import com.flashsale.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Efficiency-layer proof for ShedLock: with Redis present, a second
 * invocation of a {@code @SchedulerLock}-guarded job within the lock window
 * does NOT execute the body — only one pod runs the scan per tick.
 *
 * <p>Deterministic, not timing-racy: {@code @SchedulerLock} on {@code sweep()}
 * uses {@code lockAtLeastFor = "5s"}, so the lock stays held for 5 seconds
 * after the first call returns. A second call milliseconds later cannot
 * acquire it and is skipped. We prove the skip by observing that a freshly
 * inserted expired order is left untouched by the second call.
 *
 * <p>Contrast with {@code OrderExpiryGuardTest}, which runs with NO Redis (no
 * real lock) and proves correctness via the atomic guard alone. Here the lock
 * IS the thing under test.
 */
@SpringBootTest
@Testcontainers
class SchedulerLockTest {

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
        // Push the real scheduler far out AND suppress the startup auto-fire,
        // so the only competitor for the lock is this test's explicit calls.
        // Without this the startup sweep could win the lock and make the
        // first assertion non-deterministic.
        registry.add("flashsale.order.expiry-scan-interval-ms",        () -> "600000");
        registry.add("flashsale.inventory.reconcile-interval-ms",      () -> "600000");
        registry.add("flashsale.order.expiry-initial-delay-ms",        () -> "600000");
        registry.add("flashsale.inventory.reconcile-initial-delay-ms", () -> "600000");
        registry.add("spring.autoconfigure.exclude",
                () -> "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration");
        registry.add("spring.kafka.bootstrap-servers", () -> "");
    }

    @Autowired OrderExpiryJob   orderExpiryJob;
    @Autowired InventoryService inventoryService;
    @Autowired ProductRepository productRepository;
    @Autowired OrderRepository  orderRepository;

    @Test
    void secondSweepWithinLockWindowIsSkipped() {
        // Sanity: the Redis-backed lock provider is actually in play (not the
        // no-op fallback) — otherwise this test would silently pass for the
        // wrong reason.
        assertThat(((RedisInventoryService) inventoryService).isRedisActive())
                .as("Redis must be active so ShedLock uses the Redis lock provider")
                .isTrue();

        Product product = productRepository.saveAndFlush(newProduct(0, 10));
        Long productId = product.getId();

        // First expired order. The first sweep acquires the lock, processes
        // it, then HOLDS the lock for lockAtLeastFor (5s) after returning.
        Order first = orderRepository.saveAndFlush(expiredOrder(productId, 2));
        orderExpiryJob.sweep();

        assertThat(orderRepository.findById(first.getId()).orElseThrow().getStatus())
                .as("first sweep ran the body and expired order #1")
                .isEqualTo(OrderStatus.EXPIRED);
        assertThat(productRepository.findById(productId).orElseThrow().getAvailableStock())
                .as("order #1 released its 2 units").isEqualTo(2);

        // Second expired order appears immediately. A second sweep WITHIN the
        // 5s lock window must fail to acquire the lock → body skipped → #2
        // left untouched.
        Order second = orderRepository.saveAndFlush(expiredOrder(productId, 3));
        orderExpiryJob.sweep();

        assertThat(orderRepository.findById(second.getId()).orElseThrow().getStatus())
                .as("second sweep was locked out by ShedLock → order #2 still CREATED")
                .isEqualTo(OrderStatus.CREATED);
        assertThat(productRepository.findById(productId).orElseThrow().getAvailableStock())
                .as("order #2 stock NOT released — the second sweep body never ran")
                .isEqualTo(2);
    }

    private static Product newProduct(int available, int total) {
        Product p = new Product();
        p.setName("Scheduler Lock Test");
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
