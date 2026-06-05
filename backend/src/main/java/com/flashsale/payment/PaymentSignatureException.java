package com.flashsale.payment;

/**
 * Thrown when a webhook payload fails signature / checksum verification.
 * Controllers catch this and return 4xx — never proceed to {@code markPaid}.
 */
public class PaymentSignatureException extends RuntimeException {
    public PaymentSignatureException(String message) {
        super(message);
    }
    public PaymentSignatureException(String message, Throwable cause) {
        super(message, cause);
    }
}
