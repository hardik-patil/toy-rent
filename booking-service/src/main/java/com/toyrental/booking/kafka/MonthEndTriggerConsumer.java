package com.toyrental.booking.kafka;

import com.toyrental.booking.couchbase.CouchbaseReportRepository;
import com.toyrental.booking.couchbase.MonthlyReportDocument;
import com.toyrental.booking.entity.MonthlyReport;
import com.toyrental.booking.entity.ProcessedEvent;
import com.toyrental.booking.entity.ReportStatus;
import com.toyrental.booking.repository.MonthlyReportRepository;
import com.toyrental.booking.repository.ProcessedEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

import static com.toyrental.booking.util.IdGenerator.shortId;

/**
 * Sprint 4 skeleton per SPRINTS.md: idempotency check (eventId + Couchbase report::yyyy-mm
 * existence, matching CLAUDE.md's Month-End Report Flow step 1) and a GENERATING placeholder row
 * in both Postgres and Couchbase. Actual aggregation, PDF generation, and MinIO upload are
 * Sprint 5 scope (ReportService/PdfGeneratorService-for-reports).
 */
@Slf4j
@Component
public class MonthEndTriggerConsumer {

    private final ProcessedEventRepository processedEventRepository;
    private final MonthlyReportRepository monthlyReportRepository;
    private final CouchbaseReportRepository couchbaseReportRepository;

    public MonthEndTriggerConsumer(ProcessedEventRepository processedEventRepository,
                                    MonthlyReportRepository monthlyReportRepository,
                                    CouchbaseReportRepository couchbaseReportRepository) {
        this.processedEventRepository = processedEventRepository;
        this.monthlyReportRepository = monthlyReportRepository;
        this.couchbaseReportRepository = couchbaseReportRepository;
    }

    @KafkaListener(topics = "month.end.trigger", groupId = "report-cg")
    public void onMonthEndTrigger(MonthEndTriggerEnvelope event) {
        MDC.put("correlationId", event.correlationId());
        try {
            int month = event.payload().month();
            int year = event.payload().year();
            log.info("Received eventId={} eventType={} month={} year={}", event.eventId(), event.eventType(), month, year);

            if (processedEventRepository.existsById(event.eventId())) {
                log.info("Skipping already-processed eventId={}", event.eventId());
                return;
            }

            if (couchbaseReportRepository.findByMonthAndYear(month, year).isPresent()) {
                log.info("Skipping month={} year={}: report already exists in Couchbase", month, year);
                recordProcessed(event);
                return;
            }

            String reportId = shortId("rpt");
            try {
                monthlyReportRepository.save(MonthlyReport.builder()
                        .id(reportId)
                        .month(month)
                        .year(year)
                        .totalBookings(0)
                        .totalRevenue(java.math.BigDecimal.ZERO)
                        .totalDeposits(java.math.BigDecimal.ZERO)
                        .pendingReturns(0)
                        .status(ReportStatus.GENERATING)
                        .build());
            } catch (DataIntegrityViolationException e) {
                log.info("Skipping month={} year={}: report already exists in Postgres (unique constraint)", month, year);
                recordProcessed(event);
                return;
            }

            couchbaseReportRepository.save(MonthlyReportDocument.builder()
                    .id(MonthlyReportDocument.documentId(month, year))
                    .reportId(reportId)
                    .month(month)
                    .year(year)
                    .status(ReportStatus.GENERATING.name())
                    .generatedAt(Instant.now())
                    .build());

            recordProcessed(event);
            log.info("Report generation placeholder created reportId={} month={} year={} "
                    + "(full aggregation/PDF generation is Sprint 5 scope)", reportId, month, year);
        } finally {
            MDC.remove("correlationId");
        }
    }

    private void recordProcessed(MonthEndTriggerEnvelope event) {
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(event.eventId())
                .eventType(event.eventType())
                .build());
    }

}
