package com.flashsale.order.dto;

public record CreateOrderCommand(
        Long productId,
        int quantity,
        String buyerName,
        String buyerEmail,
        String buyerPhone,
        String shippingAddress,
        String provider
) {
    /**
     * Backward-compat constructor for tests and callers that pre-date the
     * provider-selection feature. Defaults to PAYUNI to match the original
     * behavior — existing PaymentIdempotencyTest still works unchanged.
     */
    public CreateOrderCommand(Long productId, int quantity,
                              String buyerName, String buyerEmail,
                              String buyerPhone, String shippingAddress) {
        this(productId, quantity, buyerName, buyerEmail, buyerPhone, shippingAddress, "PAYUNI");
    }
}
