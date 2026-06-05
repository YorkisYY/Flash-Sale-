package com.flashsale.payment;

import com.flashsale.domain.Order;
import com.flashsale.domain.OrderStatus;
import com.flashsale.domain.Product;
import com.flashsale.domain.ProductStatus;
import com.flashsale.order.OrderService;
import com.flashsale.order.dto.CreateOrderCommand;
import com.flashsale.repository.OrderRepository;
import com.flashsale.repository.PaymentEventRepository;
import com.flashsale.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stripe webhook idempotency. Sends the same checkout.session.completed
 * payload twice with a real HMAC-SHA256 Stripe-Signature header; asserts:
 *   - first call: 200 OK, order → PAID, exactly one payment_event row.
 *   - second call: 200 OK (no error), order stays PAID, no extra payment_event row.
 *
 * Bad-signature path is asserted separately: tampering with the body without
 * recomputing the signature yields 400.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class StripeWebhookIdempotencyTest {

    private static final String WEBHOOK_SECRET = "whsec_test_idempotency_check";

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("flashsale_test")
            .withUsername("flashsale")
            .withPassword("flashsale");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.autoconfigure.exclude",
                () -> "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration");
        // Stripe API isn't actually called by verifyCallback — these stay placeholders.
        registry.add("flashsale.payment.stripe.secret-key",     () -> "sk_test_dummy");
        registry.add("flashsale.payment.stripe.webhook-secret", () -> WEBHOOK_SECRET);
        registry.add("flashsale.payment.stripe.success-url",    () -> "http://localhost:3000/result");
        registry.add("flashsale.payment.stripe.cancel-url",     () -> "http://localhost:3000/result");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ProductRepository productRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired PaymentEventRepository paymentEventRepository;
    // @SpyBean preserves real OrderService behavior for non-stubbed calls
    // (seedOrder still works) but lets a specific test force markPaid to
    // throw without touching the real DB state.
    @SpyBean OrderService orderService;

    @Test
    void duplicateStripeEventTransitionsOrderOnce() throws Exception {
        Order order = seedOrder("STRIPE");
        String eventId = "evt_test_idem_001";

        String payload = """
            {"id":"%s","object":"event","type":"checkout.session.completed",
             "data":{"object":{"id":"cs_test_idem_001","object":"checkout.session",
                               "metadata":{"orderId":"%d"}}},
             "created":%d}""".formatted(eventId, order.getId(), Instant.now().getEpochSecond());
        String sig = stripeSignature(payload, WEBHOOK_SECRET);

        long eventsBefore = paymentEventRepository.count();

        // 1st call
        mockMvc.perform(post("/api/payments/stripe/callback")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", sig)
                .content(payload))
                .andExpect(status().isOk());

        Order paid = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(paid.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(paid.getProviderRef()).isEqualTo(eventId);
        assertThat(paymentEventRepository.count()).isEqualTo(eventsBefore + 1);

        // 2nd call (duplicate)
        mockMvc.perform(post("/api/payments/stripe/callback")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", sig)
                .content(payload))
                .andExpect(status().isOk());

        Order stillPaid = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(stillPaid.getStatus()).isEqualTo(OrderStatus.PAID);
        // Same provider_txn_id ⇒ unique constraint absorbed the dup.
        assertThat(paymentEventRepository.count()).isEqualTo(eventsBefore + 1);
    }

    /**
     * Tx rollback proof: when markPaid throws (the case the user called out —
     * webhook arrives, dedup row gets inserted, but the order-state UPDATE
     * fails for some reason), the PaymentEvent row MUST NOT remain.
     *
     * Otherwise the next retry of the same Stripe event would hit the unique
     * constraint, be silently absorbed as a "duplicate," and the order would
     * sit in CREATED forever — exactly the stranded-order failure mode the
     * tx fix is supposed to prevent.
     *
     * We use @SpyBean to force markPaid to throw on a real, existing order;
     * the existsById pre-check in applyResult therefore passes, saveAndFlush
     * inserts the dedup row, markPaid then throws → whole tx rolls back →
     * dedup row gone.
     */
    @Test
    void markPaidFailureRollsBackPaymentEvent() throws Exception {
        Order order = seedOrder("STRIPE");
        String eventId = "evt_test_rollback_001";

        // Force the order-state update to fail mid-tx. Real-world analogue:
        // DB lock timeout, downstream side-effect failure, etc.
        doThrow(new RuntimeException("simulated markPaid failure"))
                .when(orderService).markPaid(eq(order.getId()), anyString());

        String payload = """
            {"id":"%s","object":"event","type":"checkout.session.completed",
             "data":{"object":{"id":"cs_test_rollback","object":"checkout.session",
                               "metadata":{"orderId":"%d"}}},
             "created":%d}""".formatted(eventId, order.getId(), Instant.now().getEpochSecond());
        String sig = stripeSignature(payload, WEBHOOK_SECRET);

        long eventsBefore = paymentEventRepository.count();

        // markPaid throws → tx rolls back → controller's catch only fires for
        // DataIntegrityViolationException (which this isn't), so the exception
        // propagates → framework returns 5xx → Stripe will retry.
        mockMvc.perform(post("/api/payments/stripe/callback")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", sig)
                .content(payload))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isGreaterThanOrEqualTo(400));

        // The crucial assertion: the dedup row must NOT have committed. If it
        // did, Stripe's next retry would be mis-classified as a duplicate and
        // the order would never get its PAID transition.
        assertThat(paymentEventRepository.findByProviderAndProviderTxnId("STRIPE", eventId))
                .as("PaymentEvent must NOT persist when markPaid throws — tx must roll back")
                .isEmpty();
        assertThat(paymentEventRepository.count()).isEqualTo(eventsBefore);

        // And the order is left untouched in CREATED, ready for the retry.
        Order untouched = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(untouched.getStatus()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    void invalidSignatureIsRejected() throws Exception {
        Order order = seedOrder("STRIPE");

        String payload = """
            {"id":"evt_bad_sig","object":"event","type":"checkout.session.completed",
             "data":{"object":{"id":"cs_bad","object":"checkout.session",
                               "metadata":{"orderId":"%d"}}},
             "created":%d}""".formatted(order.getId(), Instant.now().getEpochSecond());
        // Sign with a DIFFERENT secret — should fail verification.
        String badSig = stripeSignature(payload, "whsec_wrong_secret");

        mockMvc.perform(post("/api/payments/stripe/callback")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", badSig)
                .content(payload))
                .andExpect(status().isBadRequest());

        Order untouched = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(untouched.getStatus()).isEqualTo(OrderStatus.CREATED);
    }

    private Order seedOrder(String provider) {
        Product p = new Product();
        p.setName("Stripe Test");
        p.setPrice(new BigDecimal("1000.00"));
        p.setTotalStock(10);
        p.setAvailableStock(10);
        p.setDropStartsAt(Instant.now().minusSeconds(60));
        p.setStatus(ProductStatus.ACTIVE);
        p = productRepository.saveAndFlush(p);

        return orderService.createOrder(new CreateOrderCommand(
                p.getId(), 1,
                "Stripe Buyer", "stripe@example.com", "0911111111", "Stripe Address",
                provider));
    }

    /** Build a valid Stripe-Signature header value: t=<unix>,v1=<hmacSha256Hex>. */
    private static String stripeSignature(String payload, String secret) {
        long ts = Instant.now().getEpochSecond();
        String signedPayload = ts + "." + payload;
        return "t=" + ts + ",v1=" + hmacSha256Hex(signedPayload, secret);
    }

    private static String hmacSha256Hex(String message, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hmac = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hmac.length * 2);
            for (byte b : hmac) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
