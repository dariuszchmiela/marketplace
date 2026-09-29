package pl.dch.marketplace.outbox;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Sends outbox rows to Kafka.
 * <ul>
 *   <li>key = aggregate id (the order id): all events of one order go to the same partition and keep their order;
 *       different orders are spread over partitions and processed in parallel;</li>
 *   <li>value = the stored JSON message unchanged (String serializer: the outbox already holds the exact bytes);</li>
 *   <li>headers = eventId, eventType, schemaVersion: routing/diagnosis without parsing the value (also kept in the DLT).</li>
 * </ul>
 * Waits synchronously for the acknowledgement ({@code acks=all}, see application.yaml): a row is marked published
 * only after the broker confirmed it.
 */
@Component
public class KafkaEventSender implements EventSender {

    public static final String HEADER_EVENT_ID = "eventId";
    public static final String HEADER_EVENT_TYPE = "eventType";
    public static final String HEADER_SCHEMA_VERSION = "schemaVersion";

    private static final Logger log = LoggerFactory.getLogger(KafkaEventSender.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxProperties properties;

    public KafkaEventSender(KafkaTemplate<String, String> kafkaTemplate, OutboxProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    @Override
    public void send(OutboxRecord record) {
        String topic = properties.topics().get(record.aggregateType());
        if (topic == null) {
            throw new EventPublicationException("No topic configured for aggregate type " + record.aggregateType(), null);
        }
        ProducerRecord<String, String> message = new ProducerRecord<>(topic, record.aggregateId(), record.payload());
        message.headers().add(HEADER_EVENT_ID, bytes(record.eventId().toString()));
        message.headers().add(HEADER_EVENT_TYPE, bytes(record.eventType()));
        message.headers().add(HEADER_SCHEMA_VERSION, bytes(Integer.toString(record.schemaVersion())));
        try {
            RecordMetadata metadata = kafkaTemplate.send(message)
                    .get(properties.publisher().sendTimeout().toMillis(), TimeUnit.MILLISECONDS)
                    .getRecordMetadata();
            log.info("outbox.sent eventId={} eventType={} aggregateId={} topic={} partition={} offset={}",
                    record.eventId(), record.eventType(), record.aggregateId(), metadata.topic(), metadata.partition(),
                    metadata.offset());
        } catch (ExecutionException ex) {
            throw new EventPublicationException("Kafka rejected the record: " + rootMessage(ex), ex.getCause());
        } catch (TimeoutException ex) {
            throw new EventPublicationException("No acknowledgement within " + properties.publisher().sendTimeout(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new EventPublicationException("Interrupted while waiting for Kafka", ex);
        } catch (RuntimeException ex) {
            // e.g. metadata not available within max.block.ms when the broker is down
            throw new EventPublicationException("Kafka send failed: " + rootMessage(ex), ex);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String rootMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + (root.getMessage() == null ? "" : ": " + root.getMessage());
    }
}
