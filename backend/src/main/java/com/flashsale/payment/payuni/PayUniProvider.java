package com.flashsale.payment.payuni;

import com.flashsale.domain.Order;
import com.flashsale.payment.PaymentProvider;
import com.flashsale.payment.PaymentResult;
import com.flashsale.payment.PaymentSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * PayUni gateway provider.
 *
 * ============================================================================
 *  SKELETON — DO NOT IMPLEMENT BLINDLY
 * ============================================================================
 * PayUni is a Taiwan-specific gateway with no widely-published SDK; the request
 * format, AES encryption mode (CBC vs ECB), padding, hex/base64 encoding, the
 * checksum algorithm, and the exact callback field names all need to come from
 * the official PayUni test-environment docs.
 *
 * Until those docs are pasted into the project, the two real methods throw.
 * Once you have the docs, implement against THEM — not against any guessed
 * field names or "this is probably how Taiwan gateways work" patterns.
 *
 * What the implementation will need to do (per spec section 7):
 *   createSession(order):
 *     1. Build the request payload (merchant id, merchant trade no, amount,
 *        item desc, return/notify URLs, etc. — exact fields per docs).
 *     2. AES-encrypt the payload with hashKey/hashIv.
 *     3. Compute the checksum / hash per docs.
 *     4. Either:
 *        a) POST to PayUni, get a redirect URL → PaymentSession.redirect(url), or
 *        b) Return a self-submitting form HTML → PaymentSession.form(html).
 *
 *   verifyCallback(payload):
 *     1. AES-decrypt the response field per docs.
 *     2. Recompute the checksum and compare — REJECT on mismatch.
 *     3. Extract the gateway transaction id and order reference.
 *     4. Return PaymentResult with the ack body PayUni expects (often "SUCCESS"
 *        or a specific string — confirm in docs).
 *
 * The merchant trade number we pass to PayUni should be order.id + the
 * paymentIdempotencyKey, so retries with the same key map to the same gateway
 * trade — this is the merchant side of idempotency.
 *
 * Test endpoint URL goes in PayUniProperties.env="test" branch; never call the
 * prod endpoint from a test-mode merchant.
 */
@Component
@EnableConfigurationProperties(PayUniProperties.class)
public class PayUniProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(PayUniProvider.class);

    public static final String PROVIDER_NAME = "PAYUNI";

    private final PayUniProperties props;

    public PayUniProvider(PayUniProperties props) {
        this.props = props;
    }

    @Override
    public String name() {
        return PROVIDER_NAME;
    }

    @Override
    public PaymentSession createSession(Order order) {
        log.warn("PayUniProvider.createSession called but PayUni API spec has not yet been provided. " +
                "Paste the PayUni test-environment docs and implement against them.");
        throw new UnsupportedOperationException(
                "PayUniProvider not yet implemented — awaiting official PayUni docs from user. " +
                "See class-level Javadoc for what's needed.");
    }

    @Override
    public PaymentResult verifyCallback(Map<String, String> payload) {
        log.warn("PayUniProvider.verifyCallback called but PayUni API spec has not yet been provided.");
        throw new UnsupportedOperationException(
                "PayUniProvider not yet implemented — awaiting official PayUni docs from user.");
    }
}
