package com.flashsale.order;

public class SoldOutException extends RuntimeException {
    private final Long productId;

    public SoldOutException(Long productId) {
        super("sold out: product " + productId);
        this.productId = productId;
    }

    public Long getProductId() { return productId; }
}
