package com.flashsale.payment;

/**
 * Signature verified, but the callback's payload timestamp is older than
 * our configured replay window — likely a captured-and-replayed legitimate
 * webhook rather than a fresh delivery from the gateway.
 *
 * Distinct from {@link PaymentSignatureException} because the response policy
 * is opposite: signature-failure returns HTTP 4xx (the request is rejected
 * as untrusted), but stale callbacks must return HTTP 200 with the gateway's
 * normal success ack — otherwise the gateway interprets non-success as a
 * delivery failure and retries the same stale message repeatedly, turning a
 * suspicious-but-harmless event into a noisy retry storm.
 */
public class StaleCallbackException extends RuntimeException {
    public StaleCallbackException(String message) {
        super(message);
    }
}
