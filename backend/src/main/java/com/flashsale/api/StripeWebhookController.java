package com.flashsale.api;

import com.flashsale.payment.PaymentResult;
import com.flashsale.payment.PaymentResultService;
import com.flashsale.payment.PaymentSignatureException;
import com.flashsale.payment.stripe.StripeProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stripe checkout.session.completed webhook.
 *
 * rawBody is the byte-for-byte body Stripe signed; passing it straight to the
 * provider preserves the HMAC. PaymentResultService.applyResult records the
 * dedup row + flips the order in one transaction (see that class for the
 * rationale).
 */
@RestController
@RequestMapping("/api/payments/stripe")
public class StripeWebhookController {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);

    private final StripeProvider stripeProvider;
    private final PaymentResultService paymentResultService;

    public StripeWebhookController(StripeProvider stripeProvider,
                                   PaymentResultService paymentResultService) {
        this.stripeProvider = stripeProvider;
        this.paymentResultService = paymentResultService;
    }

    @PostMapping("/callback")
    public ResponseEntity<String> callback(
            @RequestBody String rawBody,
            @RequestHeader("Stripe-Signature") String signatureHeader) {

        PaymentResult result;
        try {
            result = stripeProvider.verifyCallback(rawBody, signatureHeader);
        } catch (PaymentSignatureException bad) {
            log.warn("Stripe webhook rejected: {}", bad.getMessage());
            return ResponseEntity.badRequest().body("INVALID_SIGNATURE");
        }

        // Properly-signed event we don't act on (e.g. payment_intent.created).
        // 200 OK so Stripe doesn't keep retrying; no payment_event row written.
        if (result.orderId() == null) {
            return ResponseEntity.ok("IGNORED");
        }

        try {
            paymentResultService.applyResult(StripeProvider.PROVIDER_NAME, result, rawBody);
        } catch (DataIntegrityViolationException duplicate) {
            // Same Stripe event.id arrived twice; unique constraint rolled back
            // the second tx. We've already applied the first one — ack so
            // Stripe stops retrying.
            log.info("Duplicate Stripe event {} absorbed", result.providerTxnId());
        } catch (RuntimeException failed) {
            // Anything else (markPaid failure, unknown order, infra hiccup):
            // the @Transactional aspect already rolled back the dedup-row
            // insert with the failing operation, so the DB is clean. Return
            // 5xx so Stripe retries against a fresh slate — no phantom event
            // row blocking the next attempt.
            log.error("Failed to apply Stripe event {}: {}",
                    result.providerTxnId(), failed.toString());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("RETRY");
        }
        return ResponseEntity.ok("OK");
    }
}
