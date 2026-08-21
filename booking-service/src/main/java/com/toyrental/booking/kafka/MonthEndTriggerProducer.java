package com.toyrental.booking.kafka;

import com.toyrental.booking.config.CorrelationIdFilter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

import static com.toyrental.booking.util.IdGenerator.shortId;

/** Publishes month.end.trigger, keyed by "month-year" per CLAUDE.md's topic table. */
@Slf4j
@Component
public class MonthEndTriggerProducer {

    private static final String TOPIC = "month.end.trigger";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public MonthEndTriggerProducer(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(int month, int year) {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        MonthEndTriggerEnvelope envelope = new MonthEndTriggerEnvelope(
                shortId("evt"), "MONTH_END_TRIGGER", "v1", Instant.now(),
                correlationId, "booking-service", new MonthEndTriggerPayload(month, year));

        String key = month + "-" + year;
        kafkaTemplate.send(TOPIC, key, envelope);
        log.info("Published eventId={} eventType=MONTH_END_TRIGGER topic={} month={} year={}",
                envelope.eventId(), TOPIC, month, year);
    }

}
