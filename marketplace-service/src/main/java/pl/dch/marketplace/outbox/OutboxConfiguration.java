package pl.dch.marketplace.outbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
                                    PlatformTransactionManager transactionManager, OutboxProperties properties) {
        return new OutboxPublisher(repository, sender, new TransactionTemplate(transactionManager), properties.publisher());
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
