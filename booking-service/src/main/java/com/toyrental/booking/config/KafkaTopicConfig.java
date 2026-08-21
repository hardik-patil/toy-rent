package com.toyrental.booking.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Explicitly provisions every topic this service publishes or consumes, rather than relying on
 * the broker's auto-create default. Partitions are pinned at 1 here, NOT the 3/6 shown in
 * CLAUDE.md's Kafka topic reference table — that table is the Sprint 7 target state. CLAUDE.md's
 * Performance Engineering section lists "Kafka: 1 partition per topic initially... Fix: increase
 * partitions after lag proven in Grafana" as an intentional bottleneck not to pre-fix, the same
 * way Sprint 1 deliberately left the composite index and Hikari pool size unfixed. month.end.trigger
 * and monthly.report.generated are pinned at 1 partition per the table regardless (that's their
 * target state too, not a bottleneck).
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

    @Bean
    public NewTopic bookingOverdueTopic() {
        return TopicBuilder.name("booking.overdue").partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic bookingOverdueDlt() {
        return TopicBuilder.name("booking.overdue.DLT").partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic paymentSuccessTopic() {
        return TopicBuilder.name("payment.success").partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic paymentSuccessDlt() {
        return TopicBuilder.name("payment.success.DLT").partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic paymentFailedTopic() {
        return TopicBuilder.name("payment.failed").partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic paymentFailedDlt() {
        return TopicBuilder.name("payment.failed.DLT").partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic monthEndTriggerTopic() {
        return TopicBuilder.name("month.end.trigger").partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic monthEndTriggerDlt() {
        return TopicBuilder.name("month.end.trigger.DLT").partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic monthlyReportGeneratedTopic() {
        return TopicBuilder.name("monthly.report.generated").partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic monthlyReportGeneratedDlt() {
        return TopicBuilder.name("monthly.report.generated.DLT").partitions(1).replicas(1).build();
    }

}
