package com.flashsale.order;

import com.flashsale.kafka.KafkaConfig;
import com.flashsale.kafka.OrderRequestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes {@link OrderRequestedEvent} to Kafka after the API layer has
 * decremented Redis stock. Returns the pre-generated externalId so the
 * controller can return it in the 202 body for the client to poll.
 *
 * <p>Producer config (in application.yml): {@code acks=all},
 * {@code enable.idempotence=true} — gives effectively-once produce
 * semantics. Combined with the consumer's externalId-based dedup, the
 * order-create side of the pipeline is effectively-once.
 *
 * <p>{@link #send} blocks on the producer future for up to 5 seconds so we
 * don't return 202 to the client before the broker has accepted the event.
 * If the publish fails, the caller's compensation path (Redis INCR) runs.
 *
 * <p>Null-safe on KafkaTemplate via {@link ObjectProvider} — tests that
 * exclude {@code KafkaAutoConfiguration} simply get a no-op service; no
 * order is ever created, but the rest of the request behaves normally
 * (e.g. rate-limit assertions don't depend on Kafka).
 */
@Service
public class OrderIngestionService {

    private static final Logger log = LoggerFactory.getLogger(OrderIngestionService.class);
    private static final long PUBLISH_TIMEOUT_SECONDS = 5;

    /** Null when Kafka autoconfig didn't run (no broker configured). */
    private final KafkaTemplate<String, OrderRequestedEvent> kafka;

    public OrderIngestionService(ObjectProvider<KafkaTemplate<String, OrderRequestedEvent>> kafkaProvider) {
        this.kafka = kafkaProvider.getIfAvailable();
        if (this.kafka == null) {
            log.info("OrderIngestionService: no KafkaTemplate bean — publish() will be a no-op");
        }
    }

    /**
     * Build the event with a fresh externalId, publish it (partition key =
     * productId for per-product ordering), block briefly on the broker ack,
     * and return the externalId so the caller can return it to the client.
     *
     * @throws IngestionFailedException if the broker doesn't accept inside
     *         the publish timeout — the caller MUST compensate Redis.
     */
    public String publishRequest(Long productId, int quantity,
                                  String buyerName, String buyerEmail,
                                  String buyerPhone, String shippingAddress,
                                  String provider) {
        String externalId = UUID.randomUUID().toString().replace("-", "");

        if (kafka == null) {
            log.warn("Kafka not configured — order {} will never be created (skipping publish)",
                    externalId);
            return externalId;
        }

        OrderRequestedEvent event = new OrderRequestedEvent(
                externalId, productId, quantity,
                buyerName, buyerEmail, buyerPhone, shippingAddress,
                provider, Instant.now());

        try {
            kafka.send(KafkaConfig.ORDERS_REQUESTED_TOPIC,
                       String.valueOf(productId), event)
                 .get(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IngestionFailedException("interrupted while publishing " + externalId, ie);
        } catch (ExecutionException | TimeoutException e) {
            throw new IngestionFailedException("publish failed for " + externalId + ": " + e, e);
        }
        log.debug("Published OrderRequestedEvent externalId={} productId={}", externalId, productId);
        return externalId;
    }

    /** Thrown when the broker doesn't accept the event in time. Caller must compensate Redis. */
    public static class IngestionFailedException extends RuntimeException {
        public IngestionFailedException(String message, Throwable cause) { super(message, cause); }
    }
}
