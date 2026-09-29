package pl.dch.orderactivity.observability;

import java.util.Collection;
import java.util.Objects;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * The Kafka consumer, passively: are the listener containers running, and do they hold partitions? No broker probe.
 * <p>
 * Part of the token-protected {@code dependencies} group, not of readiness: the HTTP API (projection reads) works
 * without Kafka, and restarting or de-registering this instance would not bring the broker back. A stopped consumer
 * is visible here and — more precisely — as growing consumer lag in the Kafka client metrics.
 */
@Component("kafkaConsumer")
class KafkaConsumerHealthIndicator implements HealthIndicator {

    static final Status DEGRADED = new Status("DEGRADED", "the consumer is not running; the projection is falling behind");

    private final KafkaListenerEndpointRegistry registry;

    KafkaConsumerHealthIndicator(KafkaListenerEndpointRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Health health() {
        Collection<MessageListenerContainer> containers = registry.getListenerContainers();
        long running = containers.stream().filter(MessageListenerContainer::isRunning).count();
        long assignedPartitions = containers.stream()
                .map(MessageListenerContainer::getAssignedPartitions)
                .filter(Objects::nonNull)
                .mapToLong(Collection::size)
                .sum();
        return (running == containers.size() && !containers.isEmpty() ? Health.up() : Health.status(DEGRADED))
                .withDetail("source", "listener containers (passive, no probe)")
                .withDetail("containers", containers.size())
                .withDetail("running", running)
                .withDetail("assignedPartitions", assignedPartitions)
                .build();
    }
}
