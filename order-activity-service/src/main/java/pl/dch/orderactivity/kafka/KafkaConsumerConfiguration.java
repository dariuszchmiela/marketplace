package pl.dch.orderactivity.kafka;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ContainerCustomizer;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import pl.dch.orderactivity.OrderActivityProperties;
import pl.dch.orderactivity.event.PermanentEventException;
import pl.dch.orderactivity.observability.OrderEventMetrics;

/**
 * Retry and dead-letter policy of the consumer.
 * <ul>
 *   <li>transient failures (any exception except {@link PermanentEventException}): redelivered in place by
 *       {@link DefaultErrorHandler} with exponential backoff, {@code max-retries} times; the partition waits
 *       meanwhile, so ordering per order is kept;</li>
 *   <li>permanent failures: not retried at all;</li>
 *   <li>then the record goes to the dead-letter topic (same partition number) with Spring Kafka's diagnostic
 *       headers (original topic/partition/offset/timestamp, exception class and message — the stack trace header is
 *       dropped); its offset is committed and the partition continues with the next record.</li>
 * </ul>
 * No Kafka transactions and no XA: the DB transaction commits first, the offset commit follows; the gap between
 * them is covered by the idempotent consumer.
 */
@Configuration
class KafkaConsumerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfiguration.class);

    /** Consumer declares the topics it depends on (same settings as the producer; creation is idempotent). */
    @Bean
    KafkaAdmin.NewTopics orderActivityTopics(OrderActivityProperties properties) {
        return new KafkaAdmin.NewTopics(
                TopicBuilder.name(properties.topics().orderEvents()).partitions(properties.topics().partitions()).replicas(1).build(),
                TopicBuilder.name(properties.topics().deadLetter()).partitions(properties.topics().partitions()).replicas(1).build());
    }

    @Bean
    DefaultErrorHandler orderEventErrorHandler(KafkaTemplate<String, String> kafkaTemplate, OrderActivityProperties properties,
                                               OrderEventMetrics metrics) {
        DeadLetterPublishingRecoverer deadLetter = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, failure) -> new TopicPartition(properties.topics().deadLetter(), record.partition()));
        deadLetter.excludeHeader(DeadLetterPublishingRecoverer.HeaderNames.HeadersToAdd.EX_STACKTRACE);

        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(properties.retry().maxRetries());
        backOff.setInitialInterval(properties.retry().initialInterval().toMillis());
        backOff.setMultiplier(properties.retry().multiplier());

        DefaultErrorHandler errorHandler = new DefaultErrorHandler((record, failure) -> {
            log.warn("event.dead_lettered eventId={} eventType={} key={} topic={} partition={} offset={} error=\"{}\"",
                    header(record, "eventId"), header(record, "eventType"), record.key(), record.topic(),
                    record.partition(), record.offset(), rootMessage(failure));
            metrics.deadLettered(header(record, "eventType"), isPermanent(failure));
            deadLetter.accept(record, failure);
        }, backOff);
        errorHandler.addNotRetryableExceptions(PermanentEventException.class);
        errorHandler.setRetryListeners((record, failure, deliveryAttempt) -> {
            if (isPermanent(failure)) {
                log.warn("event.failed permanent=true eventId={} key={} partition={} offset={} error=\"{}\"",
                        header(record, "eventId"), record.key(), record.partition(), record.offset(), rootMessage(failure));
            } else {
                metrics.retry(header(record, "eventType"));
                log.warn("event.retry eventId={} key={} partition={} offset={} failedAttempt={} maxRetries={} error=\"{}\"",
                        header(record, "eventId"), record.key(), record.partition(), record.offset(), deliveryAttempt,
                        properties.retry().maxRetries(), rootMessage(failure));
            }
        });
        return errorHandler;
    }

    /** Makes the delivery attempt available to the listener ({@code KafkaHeaders.DELIVERY_ATTEMPT}) for logging. */
    @Bean
    ContainerCustomizer<Object, Object, ConcurrentMessageListenerContainer<Object, Object>> deliveryAttemptHeader() {
        return container -> container.getContainerProperties().setDeliveryAttemptHeader(true);
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static boolean isPermanent(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof PermanentEventException) {
                return true;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
