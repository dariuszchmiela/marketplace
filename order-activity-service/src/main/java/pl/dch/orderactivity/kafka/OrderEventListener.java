package pl.dch.orderactivity.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import pl.dch.orderactivity.activity.OrderActivityProjector;
import pl.dch.orderactivity.event.OrderEvent;
import pl.dch.orderactivity.event.OrderEventParser;
import pl.dch.orderactivity.observability.OrderEventMetrics;

/**
 * Consumes order events. The listener itself does no business work: parse (permanent failure if invalid),
 * then one transactional projector call. When it returns normally, the container commits the offset
 * (ack-mode RECORD) — i.e. strictly after the local transaction committed. If it throws, the offset is not
 * committed and {@code DefaultErrorHandler} decides: redeliver (transient) or dead-letter (permanent/exhausted).
 */
@Component
class OrderEventListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);

    private final OrderEventParser parser;
    private final OrderActivityProjector projector;
    private final OrderEventMetrics metrics;

    OrderEventListener(OrderEventParser parser, OrderActivityProjector projector, OrderEventMetrics metrics) {
        this.parser = parser;
        this.projector = projector;
        this.metrics = metrics;
    }

    @KafkaListener(topics = "${order-activity.topics.order-events}", groupId = "${spring.kafka.consumer.group-id}")
    void onOrderEvent(ConsumerRecord<String, String> record,
                      @Header(name = KafkaHeaders.DELIVERY_ATTEMPT, required = false) Integer deliveryAttempt) {
        log.info("event.received eventId={} eventType={} key={} topic={} partition={} offset={} attempt={}",
                header(record, "eventId"), header(record, "eventType"), record.key(), record.topic(),
                record.partition(), record.offset(), deliveryAttempt == null ? 1 : deliveryAttempt);
        long start = System.nanoTime();
        String eventType = header(record, "eventType");
        try {
            OrderEvent event = parser.parse(record.value());
            OrderActivityProjector.Result result = projector.apply(event);
            metrics.handled(eventType, OrderEventMetrics.Result.valueOf(result.name()), elapsedSince(start));
        } catch (RuntimeException ex) {
            metrics.handled(eventType, OrderEventMetrics.Result.FAILED, elapsedSince(start));
            throw ex;
        }
    }

    private static Duration elapsedSince(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        org.apache.kafka.common.header.Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
