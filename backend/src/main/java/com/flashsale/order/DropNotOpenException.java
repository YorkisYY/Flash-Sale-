package com.flashsale.order;

import java.time.Instant;

public class DropNotOpenException extends RuntimeException {
    private final Instant opensAt;

    public DropNotOpenException(Instant opensAt) {
        super("drop opens at " + opensAt);
        this.opensAt = opensAt;
    }

    public Instant getOpensAt() { return opensAt; }
}
