package com.flashsale.payment;

/**
 * Parsed + verified result of a provider's notify/return callback.
 *
 * @param success         true if the gateway considers the payment captured
 * @param orderId         the merchant-side order id we passed in when creating the session
 * @param providerTxnId   the gateway's transaction id — drives idempotency
 *                        (UNIQUE(provider, provider_txn_id) on payment_event)
 * @param ackResponse     the literal body to write back to the gateway as ack
 *                        (e.g. ECPay expects "1|OK"). Null = a default 200 OK is fine.
 */
public record PaymentResult(
        boolean success,
        Long orderId,
        String providerTxnId,
        String ackResponse
) {}
