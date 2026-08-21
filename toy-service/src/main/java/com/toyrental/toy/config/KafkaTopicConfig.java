package com.toyrental.toy.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Explicitly provisions the topics this service consumes, rather than relying on the broker's
 * auto-create default. Partitions are pinned at 1 here, NOT the 6 shown in CLAUDE.md's Kafka
 * topic reference table — that table is the Sprint 7 target state. CLAUDE.md's Performance
 * Engineering section lists "Kafka: 1 partition per topic initially... Fix: increase to 6
 * partitions after lag proven in Grafana" as an intentional bottleneck not to pre-fix, the same
 * way Sprint 1 deliberately left the composite index and Hikari pool size unfixed.
 */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic bookingConfirmedTopic() {
        return TopicBuilder.name("booking.confirmed").partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic bookingConfirmedDlt() {
        return TopicBuilder.name("booking.confirmed.DLT").partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic bookingCancelledTopic() {
        return TopicBuilder.name("booking.cancelled").partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic bookingCancelledDlt() {
        return TopicBuilder.name("booking.cancelled.DLT").partitions(1).replicas(1).build();
    }

}
