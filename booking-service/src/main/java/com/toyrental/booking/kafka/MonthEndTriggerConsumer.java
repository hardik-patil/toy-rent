package com.toyrental.booking.kafka;

import com.toyrental.booking.couchbase.CouchbaseReportRepository;
import com.toyrental.booking.couchbase.MonthlyReportDocument;
import com.toyrental.booking.dto.ReportAggregate;
import com.toyrental.booking.entity.MonthlyReport;
import com.toyrental.booking.entity.ProcessedEvent;
import com.toyrental.booking.entity.ReportStatus;
import com.toyrental.booking.repository.MonthlyReportRepository;
import com.toyrental.booking.repository.ProcessedEventRepository;
import com.toyrental.booking.service.MinioService;
import com.toyrental.booking.service.PdfGeneratorService;
import com.toyrental.booking.service.ReportService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static com.toyrental.booking.util.IdGenerator.shortId;

/**
 * Full month-end report flow per CLAUDE.md's "Month-End Report Flow": idempotency (eventId +
 * Couchbase existence), a GENERATING placeholder, aggregation, PDF generation, MinIO upload,
 * then SUCCESS (or FAILED) in both Postgres and Couchbase, finishing with
 * monthly.report.generated.
 */
@Slf4j
@Component
public class MonthEndTriggerConsumer {

    private final ProcessedEventRepository processedEventRepository;
    private final MonthlyReportRepository monthlyReportRepository;
    private final CouchbaseReportRepository couchbaseReportRepository;
    private final ReportService reportService;
    private final PdfGeneratorService pdfGeneratorService;
    private final MinioService minioService;
    private final MonthlyReportGeneratedProducer monthlyReportGeneratedProducer;
    private final Counter reportSuccessCounter;
    private final Counter reportFailedCounter;

    public MonthEndTriggerConsumer(ProcessedEventRepository processedEventRepository,
                                    MonthlyReportRepository monthlyReportRepository,
                                    CouchbaseReportRepository couchbaseReportRepository,
                                    ReportService reportService,
                                    PdfGeneratorService pdfGeneratorService,
                                    MinioService minioService,
                                    MonthlyReportGeneratedProducer monthlyReportGeneratedProducer,
                                    MeterRegistry meterRegistry) {
        this.processedEventRepository = processedEventRepository;
        this.monthlyReportRepository = monthlyReportRepository;
        this.couchbaseReportRepository = couchbaseReportRepository;
        this.reportService = reportService;
        this.pdfGeneratorService = pdfGeneratorService;
        this.minioService = minioService;
        this.monthlyReportGeneratedProducer = monthlyReportGeneratedProducer;
        this.reportSuccessCounter = Counter.builder("monthly.report.generated.total")
                .tag("status", "SUCCESS").description("Monthly reports generated").register(meterRegistry);
        this.reportFailedCounter = Counter.builder("monthly.report.generated.total")
                .tag("status", "FAILED").description("Monthly reports generated").register(meterRegistry);
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
            MonthlyReport report;
            try {
                report = monthlyReportRepository.saveAndFlush(MonthlyReport.builder()
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

            generateReport(event, report, month, year);
        } finally {
            MDC.remove("correlationId");
        }
    }

    private void generateReport(MonthEndTriggerEnvelope event, MonthlyReport report, int month, int year) {
        try {
            ReportAggregate aggregate = reportService.aggregate(month, year);
            byte[] pdf = pdfGeneratorService.generateMonthlyReportPdf(report, aggregate);
            String pdfPath = minioService.uploadReport(pdf, month, year);

            report.setTotalBookings(aggregate.totalBookings());
            report.setTotalRevenue(aggregate.totalRevenue());
            report.setTotalDeposits(aggregate.totalDeposits());
            report.setPendingReturns(aggregate.pendingReturns());
            if (aggregate.topToy() != null) {
                report.setTopToyId(aggregate.topToy().toyId());
                report.setTopToyName(aggregate.topToy().name());
            }
            report.setPdfStoragePath(pdfPath);
            report.setStatus(ReportStatus.SUCCESS);
            report.setGeneratedAt(LocalDateTime.now());
            monthlyReportRepository.save(report);

            couchbaseReportRepository.save(toDocument(report, aggregate, pdfPath, ReportStatus.SUCCESS));
            recordProcessed(event);
            monthlyReportGeneratedProducer.publish(report.getId(), month, year, ReportStatus.SUCCESS.name());
            reportSuccessCounter.increment();
            log.info("Report generated reportId={} month={} year={} pdfPath={}", report.getId(), month, year, pdfPath);
        } catch (RuntimeException e) {
            log.error("Report generation failed reportId={} month={} year={}", report.getId(), month, year, e);
            report.setStatus(ReportStatus.FAILED);
            monthlyReportRepository.save(report);
            recordProcessed(event);
            monthlyReportGeneratedProducer.publish(report.getId(), month, year, ReportStatus.FAILED.name());
            reportFailedCounter.increment();
        }
    }

    private MonthlyReportDocument toDocument(MonthlyReport report, ReportAggregate aggregate, String pdfPath, ReportStatus status) {
        MonthlyReportDocument.TopToy topToy = aggregate.topToy() == null ? null : MonthlyReportDocument.TopToy.builder()
                .toyId(aggregate.topToy().toyId())
                .name(aggregate.topToy().name())
                .rentals(aggregate.topToy().rentals())
                .build();

        List<MonthlyReportDocument.RevenueByWeek> revenueByWeek = aggregate.revenueByWeek().stream()
                .map(w -> MonthlyReportDocument.RevenueByWeek.builder().week(w.week()).revenue(w.revenue()).build())
                .toList();

        return MonthlyReportDocument.builder()
                .id(MonthlyReportDocument.documentId(report.getMonth(), report.getYear()))
                .reportId(report.getId())
                .month(report.getMonth())
                .year(report.getYear())
                .totalBookings(aggregate.totalBookings())
                .totalRevenue(aggregate.totalRevenue())
                .totalDeposits(aggregate.totalDeposits())
                .pendingReturns(aggregate.pendingReturns())
                .topToy(topToy)
                .revenueByWeek(revenueByWeek)
                .pdfStoragePath(pdfPath)
                .status(status.name())
                .generatedAt(Instant.now())
                .build();
    }

    private void recordProcessed(MonthEndTriggerEnvelope event) {
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(event.eventId())
                .eventType(event.eventType())
                .build());
    }

}
