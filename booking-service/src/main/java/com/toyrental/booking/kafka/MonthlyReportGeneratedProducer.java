package com.toyrental.booking.kafka;

import com.toyrental.booking.config.CorrelationIdFilter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

import static com.toyrental.booking.util.IdGenerator.shortId;

/** Publishes monthly.report.generated, keyed by "month-year" per CLAUDE.md's topic table. */
@Slf4j
@Component
public class MonthlyReportGeneratedProducer {

    private static final String TOPIC = "monthly.report.generated";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public MonthlyReportGeneratedProducer(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(String reportId, int month, int year, String status) {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        MonthlyReportGeneratedEnvelope envelope = new MonthlyReportGeneratedEnvelope(
                shortId("evt"), "MONTHLY_REPORT_GENERATED", "v1", Instant.now(),
                correlationId, "booking-service", new MonthlyReportGeneratedPayload(reportId, month, year, status));

        kafkaTemplate.send(TOPIC, month + "-" + year, envelope);
        log.info("Published eventId={} eventType=MONTHLY_REPORT_GENERATED topic={} reportId={} status={}",
                envelope.eventId(), TOPIC, reportId, status);
    }

}
