package com.flashsale.kafka;

import java.time.Instant;

/**
 * Wire-level event published by the purchase API after a successful Redis
 * Lua DECR. Consumed by {@code OrderEventConsumer} which performs the DB
 * UPDATE + order INSERT.
 *
 * <p>Carries the pre-generated {@code externalId} — that is the consumer's
 * idempotency key. At-least-once delivery + duplicate-key skip on
 * {@code orders.external_id} unique constraint = effectively-once semantics.
 *
 * <p>JSON-serialised. Adding fields is forward-compatible (Jackson ignores
 * unknown / missing); removing or renaming requires a producer-then-consumer
 * rollout. Versioned topic is out of scope for v1.
 */
public record OrderRequestedEvent(
        String externalId,
        Long productId,
        Integer quantity,
        String buyerName,
        String buyerEmail,
        String buyerPhone,
        String shippingAddress,
        String provider,
        Instant requestedAt
) {}
