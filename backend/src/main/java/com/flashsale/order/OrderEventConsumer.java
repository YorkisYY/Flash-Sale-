package com.flashsale.order;

import com.flashsale.kafka.OrderRequestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Consumes {@link OrderRequestedEvent} from {@code orders.requested} and
 * delegates to {@link OrderService#createOrderFromEvent}.
 *
 * <p>--- Manual ack discipline ---
 *
 *  {@code AckMode.MANUAL} (set in application.yml). We acknowledge ONLY
 *  after {@code createOrderFromEvent} returns normally — which means the
 *  underlying {@code @Transactional} has already committed (the order row
 *  is durable + the DB stock is final). If the JVM dies between commit and
 *  ack, redelivery happens and the consumer's idempotency guard (unique
 *  externalId) absorbs the duplicate. Crashing the other way — ack-then-
 *  process — would lose the event entirely, which we refuse to accept.
 *
 * <p>--- Failure isolation ---
 *
 *  Any exception thrown out of this method is caught by Spring Kafka's
 *  {@link org.springframework.kafka.listener.DefaultErrorHandler} (see
 *  {@code KafkaConfig#kafkaErrorHandler}). It backs off, retries 3 times,
 *  then logs at ERROR and skips the record so the partition doesn't wedge.
 *  We do not ack inside the catch — the error handler controls retries.
 *
 * <p>--- @ConditionalOnExpression ---
 *
 *  Only registered when {@code spring.kafka.bootstrap-servers} resolves to
 *  a non-empty string. Test classes that opt out of Kafka set the property
 *  to {@code ""} (in addition to excluding {@code KafkaAutoConfiguration}),
 *  so this annotation evaluates false and the listener isn't registered.
 *  {@code @ConditionalOnProperty} alone would match an empty value too,
 *  which would force every test class to do extra wiring.
 */
@Component
@ConditionalOnExpression("'${spring.kafka.bootstrap-servers:}' != ''")
public class OrderEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);

    private final OrderService orderService;

    public OrderEventConsumer(OrderService orderService) {
        this.orderService = orderService;
    }

    @KafkaListener(topics = "orders.requested", groupId = "order-writer")
    public void onOrderRequested(@Payload OrderRequestedEvent event, Acknowledgment ack) {
        log.debug("Consuming event externalId={} productId={}", event.externalId(), event.productId());
        try {
            orderService.createOrderFromEvent(event);
            ack.acknowledge();
        } catch (RuntimeException e) {
            // No ack — let the error handler retry. If retries exhaust, the
            // error handler logs + skips (so the partition doesn't wedge).
            log.warn("Consumer failed externalId={} (will retry): {}",
                    event.externalId(), e.toString());
            throw e;
        }
    }
}
