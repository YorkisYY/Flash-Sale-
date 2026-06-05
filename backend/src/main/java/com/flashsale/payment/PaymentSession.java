package com.flashsale.payment;

/**
 * Result of opening a payment session with the provider.
 *
 * One of:
 *   - {@code redirectUrl} populated (gateway hosts a checkout page; browser navigates).
 *   - {@code formHtml}    populated (gateway requires a POST form-submit, e.g. ECPay-style).
 *
 * PayUni's actual mechanic will be one of these once the docs are in — the
 * interface accommodates either.
 */
public record PaymentSession(String redirectUrl, String formHtml) {
    public static PaymentSession redirect(String url) {
        return new PaymentSession(url, null);
    }
    public static PaymentSession form(String html) {
        return new PaymentSession(null, html);
    }
}
