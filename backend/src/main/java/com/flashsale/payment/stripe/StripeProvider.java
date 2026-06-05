package com.flashsale.payment.stripe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.domain.Order;
import com.flashsale.payment.PaymentProvider;
import com.flashsale.payment.PaymentResult;
import com.flashsale.payment.PaymentSession;
import com.flashsale.payment.PaymentSignatureException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Stripe Checkout integration.
 *
 *  createSession:
 *    Builds a hosted Checkout Session in payment mode. orderId goes in
 *    session metadata; success/cancel URLs point at the frontend result page.
 *    We return the session URL — the frontend redirects the buyer there.
 *
 *  verifyCallback(rawBody, sigHeader):
 *    Uses Stripe's official Webhook.constructEvent on the *raw* request body
 *    (Spring's parsed/re-serialized body would break HMAC). Any signature
 *    mismatch throws PaymentSignatureException → controller returns 400.
 *    Only checkout.session.completed events drive the PAID transition; other
 *    event types are acked but skip the order update.
 *
 *  Idempotency:
 *    The Stripe event.id is the providerTxnId we record on PaymentEvent.
 *    Stripe retries the same event.id on delivery failure, so the UNIQUE
 *    (provider, provider_txn_id) constraint absorbs duplicates upstream of
 *    the order-state change.
 *
 *  Amount handling:
 *    TWD is a zero-decimal currency in Stripe — unit_amount is the integer
 *    number of TWD, not cents. For currencies with fractional units, switch
 *    to multiplying by 100 (and verify the precision in BigDecimal).
 */
@Component
@EnableConfigurationProperties(StripeProperties.class)
public class StripeProvider implements PaymentProvider {

    public static final String PROVIDER_NAME = "STRIPE";
    private static final Logger log = LoggerFactory.getLogger(StripeProvider.class);

    private final StripeProperties props;
    private final ObjectMapper objectMapper;

    public StripeProvider(StripeProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return PROVIDER_NAME;
    }

    @Override
    public PaymentSession createSession(Order order) {
        if (props.getSecretKey() == null || props.getSecretKey().isBlank()) {
            throw new IllegalStateException("STRIPE_SECRET_KEY is not configured");
        }

        long unitAmount = unitAmountFor(order);
        String redirectBase = props.getSuccessUrl();
        String cancelBase   = props.getCancelUrl();

        SessionCreateParams params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl(redirectBase + "/" + order.getId())
                .setCancelUrl(cancelBase + "/" + order.getId())
                .setClientReferenceId(order.getPaymentIdempotencyKey())
                .putMetadata("orderId", String.valueOf(order.getId()))
                .setPaymentIntentData(
                        SessionCreateParams.PaymentIntentData.builder()
                                .putMetadata("orderId", String.valueOf(order.getId()))
                                .build())
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setQuantity((long) order.getQuantity())
                        .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                                .setCurrency(props.getCurrency())
                                .setUnitAmount(unitAmount)
                                .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                        .setName("Flash Sale Order #" + order.getId())
                                        .build())
                                .build())
                        .build())
                .build();

        RequestOptions options = RequestOptions.builder()
                .setApiKey(props.getSecretKey())
                .build();

        Session session;
        try {
            session = Session.create(params, options);
        } catch (StripeException e) {
            throw new IllegalStateException("Stripe session create failed: " + e.getMessage(), e);
        }
        log.info("Stripe session created: order={} session={}", order.getId(), session.getId());
        return PaymentSession.redirect(session.getUrl());
    }

    /**
     * Stripe doesn't deliver form-encoded callbacks. Force callers onto the
     * raw-body path so we never try to verify a re-serialized body.
     */
    @Override
    public PaymentResult verifyCallback(Map<String, String> payload) {
        throw new UnsupportedOperationException("Stripe uses raw-body webhooks; call verifyCallback(rawBody, sigHeader)");
    }

    @Override
    public PaymentResult verifyCallback(String rawBody, String signatureHeader) {
        if (props.getWebhookSecret() == null || props.getWebhookSecret().isBlank()) {
            throw new IllegalStateException("STRIPE_WEBHOOK_SECRET is not configured");
        }

        Event event;
        try {
            event = Webhook.constructEvent(rawBody, signatureHeader, props.getWebhookSecret());
        } catch (SignatureVerificationException e) {
            throw new PaymentSignatureException("Stripe webhook signature invalid", e);
        }

        // Only checkout.session.completed drives the PAID transition. Other
        // event types are valid + signed, we just don't act on them.
        if (!"checkout.session.completed".equals(event.getType())) {
            log.debug("Stripe event {} of type {} acknowledged but not actionable",
                    event.getId(), event.getType());
            return new PaymentResult(false, null, event.getId(), null);
        }

        // The Stripe SDK can be finicky about deserializing events whose
        // api_version doesn't match the SDK. Reading metadata.orderId from the
        // raw JSON sidesteps that entirely.
        Long orderId = extractOrderId(rawBody);
        return new PaymentResult(true, orderId, event.getId(), null);
    }

    private long unitAmountFor(Order order) {
        // amount = price * quantity; per-unit = amount / quantity.
        BigDecimal perUnit = order.getAmount().divide(
                BigDecimal.valueOf(order.getQuantity()),
                0, RoundingMode.HALF_UP);
        return perUnit.longValueExact();
    }

    private Long extractOrderId(String rawBody) {
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            JsonNode metadata = root.path("data").path("object").path("metadata");
            String orderIdStr = metadata.path("orderId").asText(null);
            if (orderIdStr == null || orderIdStr.isBlank()) {
                throw new IllegalStateException(
                        "Stripe checkout.session.completed missing metadata.orderId");
            }
            return Long.parseLong(orderIdStr);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot extract orderId from Stripe payload: " + e.getMessage(), e);
        }
    }
}
