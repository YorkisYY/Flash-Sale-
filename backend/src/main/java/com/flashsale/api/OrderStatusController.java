package com.flashsale.api;

import com.flashsale.domain.Order;
import com.flashsale.payment.PaymentProvider;
import com.flashsale.payment.PaymentProviderRegistry;
import com.flashsale.payment.PaymentSession;
import com.flashsale.repository.OrderRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Lightweight polling endpoint for the async-pipeline result page.
 *
 * <p>The 202 returned by {@code POST /purchase} carries the pre-generated
 * {@code externalId} — the client polls THIS endpoint with that id until
 * the response status field leaves {@code PROCESSING}.
 *
 * <p>--- The "PROCESSING when missing" contract ---
 *
 *  When the consumer hasn't written the row yet, the order row simply
 *  doesn't exist. We deliberately do NOT return 404 in that window — the
 *  client is following the documented contract and a 404 would look like
 *  the order failed. Instead the endpoint returns a 200 with
 *  {@code {"status":"PROCESSING"}} until the row appears, at which point
 *  the real order status (CREATED / PAID / EXPIRED / etc.) takes over.
 *
 *  When the row never appears (consumer rejected on DB drift / Redis
 *  compensation ran) the client stays in PROCESSING until it gives up. That
 *  matches the design intent: from the buyer's perspective the request was
 *  accepted but not fulfilled, which is exactly true.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderStatusController {

    private final OrderRepository orderRepository;
    private final PaymentProviderRegistry providers;

    public OrderStatusController(OrderRepository orderRepository,
                                 PaymentProviderRegistry providers) {
        this.orderRepository = orderRepository;
        this.providers = providers;
    }

    @GetMapping("/{externalId}/status")
    public ResponseEntity<Map<String, String>> getStatus(@PathVariable String externalId) {
        return orderRepository.findByExternalId(externalId)
                .map(order -> ResponseEntity.ok(Map.of("status", order.getStatus().name())))
                .orElseGet(() -> ResponseEntity.ok(Map.of("status", "PROCESSING")));
    }

    /**
     * Create the gateway-side payment session for an existing CREATED order
     * — used by the result page's "Pay now" button. The async ingestion path
     * deliberately doesn't return a redirect URL in the 202, so this is the
     * separate handoff once the consumer has written the row.
     *
     * Provider is taken from {@code Order.provider} (set at request time
     * from the buyer's choice); we delegate to the matching
     * {@link PaymentProvider#createSession}.
     */
    @PostMapping("/{externalId}/payment-session")
    public ResponseEntity<Map<String, Object>> createPaymentSession(@PathVariable String externalId) {
        Order order = orderRepository.findByExternalId(externalId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "order not found: " + externalId));
        PaymentProvider provider = providers.require(order.getProvider());
        PaymentSession session = provider.createSession(order);
        Map<String, Object> body = new HashMap<>();
        body.put("orderId", externalId);
        body.put("redirectUrl", session.redirectUrl()); // null for ECPay; non-null for Stripe
        body.put("formHtml",    session.formHtml());    // non-null for ECPay; null for Stripe
        return ResponseEntity.ok(body);
    }
}
