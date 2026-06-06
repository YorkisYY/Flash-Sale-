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
 * --- Raw-body invariant (do not regress) ---
 *
 *  The `rawBody` parameter is declared as {@code @RequestBody String} so Spring's
 *  StringHttpMessageConverter hands us the body byte-for-byte — exactly what
 *  Stripe computed the HMAC over. The instant any typed converter (Jackson →
 *  POJO, form binder, even re-serialisation back to JSON) touches this body,
 *  the byte sequence changes (whitespace normalisation, field reordering,
 *  Unicode escape forms, trailing newline trimming) and {@link
 *  com.stripe.net.Webhook#constructEvent} HMAC check fails. EVERY legit Stripe
 *  webhook would return 400 INVALID_SIGNATURE in production.
 *
 *  Concrete rules for anyone touching this file:
 *    - Do NOT change the parameter type to a DTO / JsonNode / Map.
 *    - Do NOT add an {@code @JsonDeserialize} or {@code @InitBinder}.
 *    - Do NOT register a request-body wrapping filter on this path.
 *    - Pass {@code rawBody} straight to {@link StripeProvider#verifyCallback}
 *      and reuse it as the payload for the dedup row's {@code raw_payload}.
 *
 * PaymentResultService.applyResult records the dedup row + flips the order
 * in one transaction (see that class for the rationale).
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
            // See class Javadoc — must stay raw String for HMAC verification.
            @RequestBody String rawBody,
            @RequestHeader("Stripe-Signature") String signatureHeader) {

        PaymentResult result;
        try {
            result = stripeProvider.verifyCallback(rawBody, signatureHeader);
        } catch (PaymentSignatureException bad) {
            log.warn("Stripe webhook rejected: {}", bad.getMessage());
            return ResponseEntity.badRequest().body("INVALID_SIGNATURE");
        }

        // Properly-signed event we don't act on:
        //   - event type other than checkout.session.completed, OR
        //   - checkout.session.completed with no parseable metadata.orderId
        //     (this is what `stripe trigger`'s fixture data looks like).
        // Either way: 200 OK so Stripe stops retrying; no payment_event row.
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
        } catch (IllegalArgumentException unknownOrder) {
            // applyResult's existsById pre-check throws this when the event
            // references an orderId we don't know about. Most common cause:
            // `stripe trigger` fixture data with a synthetic orderId; or a
            // stale event from a different deployment/restored snapshot.
            // Either way it's NOT a server error — there's nothing to retry,
            // we just don't have the order. Ack so Stripe stops trying.
            log.warn("Stripe event {} references unknown order — acking to stop retries: {}",
                    result.providerTxnId(), unknownOrder.getMessage());
            return ResponseEntity.ok("UNKNOWN_ORDER");
        } catch (RuntimeException failed) {
            // Anything else (markPaid failure, infra hiccup, etc.): the
            // @Transactional aspect already rolled back the dedup-row insert
            // with the failing operation, so the DB is clean. Return 5xx so
            // Stripe retries against a fresh slate — no phantom event row
            // blocking the next attempt.
            log.error("Failed to apply Stripe event {}: {}",
                    result.providerTxnId(), failed.toString());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("RETRY");
        }
        return ResponseEntity.ok("OK");
    }
}
