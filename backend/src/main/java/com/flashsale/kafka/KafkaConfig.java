package com.flashsale.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import org.apache.kafka.clients.admin.NewTopic;

/**
 * Wires the {@code orders.requested} topic explicitly (auto-create is OFF
 * in the broker) and the listener-side retry policy.
 *
 * <p>Three partitions so multiple products can be processed in parallel by
 * different consumer threads. Partition key = productId, so all events for
 * the same product land on one partition and are processed sequentially —
 * which is what we want for the single-product hot-row contention.
 *
 * <p>{@link DefaultErrorHandler} retries 3 times with 1s backoff. On
 * exhaustion the failure is logged at ERROR; the record is then committed
 * as processed (skipping the bad record) so the consumer doesn't get stuck.
 *
 * <p>TODO dead-letter topic: in v1 a permanently-failing record is just
 * logged. A future enhancement would publish to {@code orders.requested.dlt}
 * with the failure metadata so ops can inspect / replay.
 *
 * <p>Only registered when {@code spring.kafka.bootstrap-servers} is set to
 * a NON-EMPTY value. The {@code @ConditionalOnProperty} default would also
 * match an empty string, which doesn't suit the test pattern of "set the
 * property to empty to opt out." {@code @ConditionalOnExpression} with an
 * explicit emptiness check is the cleanest way to express "real broker
 * address required".
 */
@Configuration
@ConditionalOnExpression("'${spring.kafka.bootstrap-servers:}' != ''")
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    public static final String ORDERS_REQUESTED_TOPIC = "orders.requested";

    @Bean
    public NewTopic ordersRequestedTopic() {
        return TopicBuilder.name(ORDERS_REQUESTED_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler() {
        // 3 retries, 1s apart. Total time the consumer is blocked on a bad
        // record before giving up: ~3s. Acceptable for v1 — we'd rather skip
        // a poison message than wedge the partition.
        DefaultErrorHandler handler = new DefaultErrorHandler(
                (ConsumerRecord<?, ?> record, Exception ex) -> {
                    // Exhaustion recoverer: log + skip. TODO publish to a DLT.
                    log.error("Exhausted retries on orders.requested partition={} offset={} key={}: {}",
                            record.partition(), record.offset(), record.key(), ex.toString());
                },
                new FixedBackOff(1000L, 3L));
        return handler;
    }
}
