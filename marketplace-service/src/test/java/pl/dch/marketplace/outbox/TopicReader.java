package pl.dch.marketplace.outbox;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;

/**
 * Test helper: reads a topic with a plain KafkaConsumer (no consumer group), starting at the current end of
 * every partition, so a test only sees what was published after it was created.
 */
final class TopicReader implements AutoCloseable {

    private final KafkaConsumer<String, String> consumer;
    private final List<ConsumerRecord<String, String>> received = new ArrayList<>();

    TopicReader(String bootstrapServers, String topic) {
        consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false));
        List<TopicPartition> partitions = consumer.partitionsFor(topic, Duration.ofSeconds(10)).stream()
                .map(info -> new TopicPartition(topic, info.partition()))
                .toList();
        consumer.assign(partitions);
        consumer.seekToEnd(partitions);
        partitions.forEach(consumer::position);   // resolve the end offsets now, before anything new is published
    }

    /** Waits (bounded) until {@code count} records matching the filter arrived; returns them in arrival order. */
    List<ConsumerRecord<String, String>> await(Predicate<ConsumerRecord<String, String>> filter, int count, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            consumer.poll(Duration.ofMillis(200)).forEach(received::add);
            List<ConsumerRecord<String, String>> matching = received.stream().filter(filter).toList();
            if (matching.size() >= count) {
                return matching;
            }
        }
        throw new AssertionError("Expected " + count + " matching records, got "
                + received.stream().filter(filter).count() + " within " + timeout);
    }

    static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        consumer.close();
    }
}
