package com.toyrental.booking.kafka;

import com.toyrental.booking.config.KafkaConsumerConfig;
import com.toyrental.booking.repository.ProcessedEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Real Kafka wire round-trip (embedded broker, no mocked Kafka client) — the "Kafka integration
 * tests" story from Sprint 4's checklist. Everything except Kafka is either mocked
 * (ProcessedEventRepository via @MockBean) or excluded (DataSource/JPA/Flyway/Security
 * autoconfiguration), so this doesn't need live Postgres/Couchbase/Feign/Keycloak the way the
 * live cross-service validation sessions did.
 */
@SpringBootTest(classes = {PaymentEventConsumer.class, KafkaConsumerConfig.class}, properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.consumer.properties.spring.json.trusted.packages=com.toyrental.*"
})
@EnableAutoConfiguration(exclude = {
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        JpaRepositoriesAutoConfiguration.class,
        FlywayAutoConfiguration.class,
        SecurityAutoConfiguration.class,
        SecurityFilterAutoConfiguration.class,
        ManagementWebSecurityAutoConfiguration.class,
        OAuth2ResourceServerAutoConfiguration.class
})
@EmbeddedKafka(partitions = 1, topics = {"payment.success", "payment.success.DLT"})
class PaymentEventConsumerIntegrationTest {

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @MockBean
    private ProcessedEventRepository processedEventRepository;

    @Test
    void realKafkaMessageIsConsumedDeserializedAndRecordedIdempotently() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        when(processedEventRepository.existsById("evt-int-001")).thenReturn(false);
        when(processedEventRepository.save(any())).thenAnswer(inv -> {
            latch.countDown();
            return inv.getArgument(0);
        });

        PaymentEventEnvelope envelope = new PaymentEventEnvelope(
                "evt-int-001", "PAYMENT_SUCCESS", "v1", Instant.now(), "corr-int-1", "booking-service",
                new PaymentEventPayload("bkg-int-001", "cust-int-001", BigDecimal.valueOf(500), null));

        kafkaTemplate.send("payment.success", "bkg-int-001", envelope);

        assertThat(latch.await(10, TimeUnit.SECONDS)).as("consumer processed the message within 10s").isTrue();
    }

}
