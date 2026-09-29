package pl.dch.marketplace.outbox;

import java.time.Clock;
import java.time.Duration;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@EnableConfigurationProperties(OutboxProperties.class)
class OutboxConfiguration {

    @Bean
    OutboxPublisher outboxPublisher(OutboxRepository repository, EventSender sender,
                                    PlatformTransactionManager transactionManager, OutboxProperties properties,
                                    OutboxMetrics outboxMetrics, OutboxTracing outboxTracing) {
        return new OutboxPublisher(repository, sender, new TransactionTemplate(transactionManager), properties.publisher(),
                outboxMetrics, outboxTracing);
    }

    /** Backlog gauges read a snapshot at most this old (one small query set per interval, however often scraped). */
    @Bean
    OutboxMetrics outboxMetrics(OutboxRepository repository, MeterRegistry meterRegistry,
                                @Value("${outbox.metrics.snapshot-max-age:5s}") Duration snapshotMaxAge) {
        return new OutboxMetrics(repository, meterRegistry, snapshotMaxAge, Clock.systemUTC());
    }

    /** Works without tracing on the classpath/enabled too: then no context is stored and no spans are created. */
    @Bean
    OutboxTracing outboxTracing(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator,
                                ObjectProvider<ObservationRegistry> observationRegistry) {
        return new OutboxTracing(tracer.getIfAvailable(() -> Tracer.NOOP), propagator.getIfAvailable(() -> Propagator.NOOP),
                observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP));
    }

    /** The topics this service produces to (created at startup if missing; producer owns its topics). */
    @Bean
    KafkaAdmin.NewTopics outboxTopics(OutboxProperties properties) {
        return new KafkaAdmin.NewTopics(properties.topics().values().stream()
                .map(topic -> TopicBuilder.name(topic).partitions(properties.topicPartitions()).replicas(1).build())
                .toArray(NewTopic[]::new));
    }

    /**
     * Polling is a separate bean so it can be switched off ({@code outbox.publisher.enabled=false}) without
     * removing the publisher: tests then trigger batches explicitly and deterministically.
     */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "outbox.publisher.enabled", havingValue = "true")
    static class OutboxPollingScheduler {

        private static final Logger log = LoggerFactory.getLogger(OutboxPollingScheduler.class);
        private static final int MAX_BATCHES_PER_POLL = 10;

        private final OutboxPublisher publisher;

        OutboxPollingScheduler(OutboxPublisher publisher) {
            this.publisher = publisher;
        }

        @Scheduled(fixedDelayString = "${outbox.publisher.poll-interval}")
        void poll() {
            try {
                publisher.publishAllPending(MAX_BATCHES_PER_POLL);
            } catch (RuntimeException ex) {
                // e.g. database unavailable: nothing is lost, the rows stay pending for the next poll
                log.warn("outbox.poll_failed error=\"{}\"", ex.getMessage());
            }
        }
    }
}
