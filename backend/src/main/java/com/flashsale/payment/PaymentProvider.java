package com.flashsale.payment;

import com.flashsale.domain.Order;

import java.util.Map;

/**
 * Payment-gateway abstraction. v1 implementations: {@code StripeProvider},
 * {@code EcpayProvider} (both working), and {@code PayUniProvider} (skeleton
 * awaiting docs).
 *
 * Two callback shapes are supported because the gateways genuinely differ:
 *   - form-encoded params (PayUni / ECPay) → {@link #verifyCallback(Map)}
 *   - raw signed body + signature header (Stripe) → {@link #verifyCallback(String, String)}
 *
 * A provider overrides whichever method matches its real API. The other
 * default-throws so the wrong call path fails loudly.
 */
public interface PaymentProvider {

    /** Identifier persisted on {@code orders.provider} and {@code payment_event.provider}. */
    String name();

    /** Open a checkout session for the order. */
    PaymentSession createSession(Order order);

    /**
     * Form-encoded notify/return callback (PayUni, ECPay).
     *
     * Implementations MUST verify the gateway's signature/checksum, then return
     * a {@link PaymentResult} containing the gateway's transaction id so the
     * caller can deduplicate on (provider, provider_txn_id).
     *
     * MUST throw {@link PaymentSignatureException} on signature mismatch — never
     * silently return success=false.
     */
    PaymentResult verifyCallback(Map<String, String> payload);

    /**
     * Raw-body + signature-header callback (Stripe).
     *
     * For providers whose webhooks are JSON-signed via an HMAC over the raw
     * request body, the body MUST stay byte-for-byte identical to what arrived
     * (Spring's body re-serialization breaks the signature). The controller
     * passes the raw String straight through.
     */
    default PaymentResult verifyCallback(String rawBody, String signatureHeader) {
        throw new UnsupportedOperationException(
                name() + " does not implement raw-body callback verification");
    }
}
