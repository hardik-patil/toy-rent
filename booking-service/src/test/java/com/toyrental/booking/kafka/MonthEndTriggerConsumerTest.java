package com.toyrental.booking.kafka;

import com.toyrental.booking.couchbase.CouchbaseReportRepository;
import com.toyrental.booking.couchbase.MonthlyReportDocument;
import com.toyrental.booking.dto.ReportAggregate;
import com.toyrental.booking.entity.MonthlyReport;
import com.toyrental.booking.entity.ReportStatus;
import com.toyrental.booking.repository.MonthlyReportRepository;
import com.toyrental.booking.repository.ProcessedEventRepository;
import com.toyrental.booking.service.MinioService;
import com.toyrental.booking.service.PdfGeneratorService;
import com.toyrental.booking.service.ReportService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MonthEndTriggerConsumerTest {

    @Mock
    private ProcessedEventRepository processedEventRepository;
    @Mock
    private MonthlyReportRepository monthlyReportRepository;
    @Mock
    private CouchbaseReportRepository couchbaseReportRepository;
    @Mock
    private ReportService reportService;
    @Mock
    private PdfGeneratorService pdfGeneratorService;
    @Mock
    private MinioService minioService;
    @Mock
    private MonthlyReportGeneratedProducer monthlyReportGeneratedProducer;

    private MonthEndTriggerConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new MonthEndTriggerConsumer(processedEventRepository, monthlyReportRepository,
                couchbaseReportRepository, reportService, pdfGeneratorService, minioService,
                monthlyReportGeneratedProducer, new SimpleMeterRegistry());
    }

    private MonthEndTriggerEnvelope event(String eventId) {
        return new MonthEndTriggerEnvelope(eventId, "MONTH_END_TRIGGER", "v1", Instant.now(),
                "corr-1", "booking-service", new MonthEndTriggerPayload(8, 2026));
    }

    private MonthlyReport placeholder() {
        return MonthlyReport.builder().id("rpt-abc12345").month(8).year(2026)
                .status(ReportStatus.GENERATING).totalRevenue(BigDecimal.ZERO).totalDeposits(BigDecimal.ZERO).build();
    }

    @Test
    void skipsAlreadyProcessedEvent() {
        when(processedEventRepository.existsById("evt-001")).thenReturn(true);

        consumer.onMonthEndTrigger(event("evt-001"));

        verify(couchbaseReportRepository, never()).findByMonthAndYear(anyInt(), anyInt());
        verify(monthlyReportRepository, never()).saveAndFlush(any());
    }

    @Test
    void skipsWhenReportAlreadyExistsInCouchbase() {
        when(processedEventRepository.existsById("evt-002")).thenReturn(false);
        when(couchbaseReportRepository.findByMonthAndYear(8, 2026))
                .thenReturn(Optional.of(MonthlyReportDocument.builder().build()));

        consumer.onMonthEndTrigger(event("evt-002"));

        verify(monthlyReportRepository, never()).saveAndFlush(any());
        verify(processedEventRepository).save(any());
    }

    @Test
    void generatesFullReportWhenNoReportExistsYet() {
        when(processedEventRepository.existsById("evt-003")).thenReturn(false);
        when(couchbaseReportRepository.findByMonthAndYear(8, 2026)).thenReturn(Optional.empty());
        when(monthlyReportRepository.saveAndFlush(any())).thenReturn(placeholder());

        ReportAggregate aggregate = new ReportAggregate(3, BigDecimal.valueOf(900), BigDecimal.valueOf(3000), 1, null, List.of());
        when(reportService.aggregate(8, 2026)).thenReturn(aggregate);
        when(pdfGeneratorService.generateMonthlyReportPdf(any(), eq(aggregate))).thenReturn(new byte[]{1, 2, 3});
        when(minioService.uploadReport(any(), eq(8), eq(2026))).thenReturn("reports/2026/08/monthly-report-2026-08.pdf");

        consumer.onMonthEndTrigger(event("evt-003"));

        ArgumentCaptor<MonthlyReport> captor = ArgumentCaptor.forClass(MonthlyReport.class);
        verify(monthlyReportRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ReportStatus.SUCCESS);
        assertThat(captor.getValue().getTotalBookings()).isEqualTo(3);
        assertThat(captor.getValue().getPdfStoragePath()).isEqualTo("reports/2026/08/monthly-report-2026-08.pdf");

        verify(couchbaseReportRepository).save(any(MonthlyReportDocument.class));
        verify(monthlyReportGeneratedProducer).publish("rpt-abc12345", 8, 2026, "SUCCESS");
        verify(processedEventRepository).save(any());
    }

    @Test
    void marksReportFailedWhenGenerationThrows() {
        when(processedEventRepository.existsById("evt-004")).thenReturn(false);
        when(couchbaseReportRepository.findByMonthAndYear(8, 2026)).thenReturn(Optional.empty());
        when(monthlyReportRepository.saveAndFlush(any())).thenReturn(placeholder());
        when(reportService.aggregate(8, 2026)).thenThrow(new RuntimeException("boom"));

        consumer.onMonthEndTrigger(event("evt-004"));

        ArgumentCaptor<MonthlyReport> captor = ArgumentCaptor.forClass(MonthlyReport.class);
        verify(monthlyReportRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ReportStatus.FAILED);

        verify(couchbaseReportRepository, never()).save(any());
        verify(monthlyReportGeneratedProducer).publish("rpt-abc12345", 8, 2026, "FAILED");
        verify(processedEventRepository).save(any());
    }

    @Test
    void skipsGracefullyOnConcurrentDuplicateInsert() {
        when(processedEventRepository.existsById("evt-005")).thenReturn(false);
        when(couchbaseReportRepository.findByMonthAndYear(8, 2026)).thenReturn(Optional.empty());
        when(monthlyReportRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

        consumer.onMonthEndTrigger(event("evt-005"));

        verify(couchbaseReportRepository, never()).save(any());
        verify(processedEventRepository).save(any());
    }

}
