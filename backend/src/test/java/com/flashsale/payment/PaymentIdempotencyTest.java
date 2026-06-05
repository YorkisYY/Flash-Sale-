package com.flashsale.payment;

import com.flashsale.domain.Order;
import com.flashsale.domain.OrderStatus;
import com.flashsale.domain.PaymentEvent;
import com.flashsale.domain.Product;
import com.flashsale.domain.ProductStatus;
import com.flashsale.order.OrderService;
import com.flashsale.order.dto.CreateOrderCommand;
import com.flashsale.repository.OrderRepository;
import com.flashsale.repository.PaymentEventRepository;
import com.flashsale.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spec section 10 idempotency check: a duplicate PayUni callback for the same
 * provider transaction must:
 *   - be rejected by the UNIQUE(provider, provider_txn_id) constraint, and
 *   - leave the order in PAID exactly once (markPaid is a no-op on repeat).
 */
@SpringBootTest
@Testcontainers
class PaymentIdempotencyTest {

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("flashsale_test")
            .withUsername("flashsale")
            .withPassword("flashsale");

    @DynamicPropertySource
    static void disableRedisAutoconfig(DynamicPropertyRegistry registry) {
        registry.add("spring.autoconfigure.exclude",
                () -> "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration");
    }

    @Autowired private ProductRepository productRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentEventRepository paymentEventRepository;
    @Autowired private OrderService orderService;

    @Test
    void duplicateProviderTxnIsRejectedByUniqueConstraint() {
        Order order = seedOrder();

        PaymentEvent first = newEvent(order.getId(), "txn-aaa-1");
        paymentEventRepository.saveAndFlush(first);

        PaymentEvent dup = newEvent(order.getId(), "txn-aaa-1");
        assertThatThrownBy(() -> paymentEventRepository.saveAndFlush(dup))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(paymentEventRepository.findByProviderAndProviderTxnId("PAYUNI", "txn-aaa-1"))
                .isPresent();
    }

    @Test
    void doubleMarkPaidLeavesOrderInPaidOnce() {
        Order order = seedOrder();
        Long orderId = order.getId();
        int qtyBefore = order.getQuantity();
        Long productId = order.getProductId();
        int availBefore = productRepository.findById(productId).orElseThrow().getAvailableStock();

        orderService.markPaid(orderId, "txn-bbb-1");
        orderService.markPaid(orderId, "txn-bbb-1"); // duplicate callback

        Order reloaded = orderRepository.findById(orderId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(reloaded.getProviderRef()).isEqualTo("txn-bbb-1");

        // Stock must NOT be double-decremented.
        int availAfter = productRepository.findById(productId).orElseThrow().getAvailableStock();
        assertThat(availAfter).isEqualTo(availBefore);
        assertThat(reloaded.getQuantity()).isEqualTo(qtyBefore);
    }

    private Order seedOrder() {
        Product p = new Product();
        p.setName("Idem Test");
        p.setPrice(new BigDecimal("1000.00"));
        p.setTotalStock(10);
        p.setAvailableStock(10);
        p.setDropStartsAt(Instant.now().minusSeconds(60));
        p.setStatus(ProductStatus.ACTIVE);
        p = productRepository.saveAndFlush(p);

        return orderService.createOrder(new CreateOrderCommand(
                p.getId(), 1,
                "Buyer", "buyer@example.com", "0900000000", "1 Main St"
        ));
    }

    private static PaymentEvent newEvent(Long orderId, String txnId) {
        PaymentEvent e = new PaymentEvent();
        e.setOrderId(orderId);
        e.setProvider("PAYUNI");
        e.setProviderTxnId(txnId);
        e.setRawPayload("{}");
        e.setProcessed(true);
        return e;
    }
}
