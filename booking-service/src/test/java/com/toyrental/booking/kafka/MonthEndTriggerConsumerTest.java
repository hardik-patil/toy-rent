package com.toyrental.booking.kafka;

import com.toyrental.booking.couchbase.CouchbaseReportRepository;
import com.toyrental.booking.couchbase.MonthlyReportDocument;
import com.toyrental.booking.entity.MonthlyReport;
import com.toyrental.booking.repository.MonthlyReportRepository;
import com.toyrental.booking.repository.ProcessedEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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

    private MonthEndTriggerConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new MonthEndTriggerConsumer(processedEventRepository, monthlyReportRepository, couchbaseReportRepository);
    }

    private MonthEndTriggerEnvelope event(String eventId) {
        return new MonthEndTriggerEnvelope(eventId, "MONTH_END_TRIGGER", "v1", Instant.now(),
                "corr-1", "booking-service", new MonthEndTriggerPayload(8, 2026));
    }

    @Test
    void skipsAlreadyProcessedEvent() {
        when(processedEventRepository.existsById("evt-001")).thenReturn(true);

        consumer.onMonthEndTrigger(event("evt-001"));

        verify(couchbaseReportRepository, never()).findByMonthAndYear(anyInt(), anyInt());
        verify(monthlyReportRepository, never()).save(any());
    }

    @Test
    void skipsWhenReportAlreadyExistsInCouchbase() {
        when(processedEventRepository.existsById("evt-002")).thenReturn(false);
        when(couchbaseReportRepository.findByMonthAndYear(8, 2026))
                .thenReturn(Optional.of(MonthlyReportDocument.builder().build()));

        consumer.onMonthEndTrigger(event("evt-002"));

        verify(monthlyReportRepository, never()).save(any());
        verify(processedEventRepository).save(any());
    }

    @Test
    void createsGeneratingPlaceholderWhenNoReportExistsYet() {
        when(processedEventRepository.existsById("evt-003")).thenReturn(false);
        when(couchbaseReportRepository.findByMonthAndYear(8, 2026)).thenReturn(Optional.empty());

        consumer.onMonthEndTrigger(event("evt-003"));

        verify(monthlyReportRepository).save(any(MonthlyReport.class));
        verify(couchbaseReportRepository).save(any(MonthlyReportDocument.class));
        verify(processedEventRepository).save(any());
    }

    @Test
    void skipsGracefullyOnConcurrentDuplicateInsert() {
        when(processedEventRepository.existsById("evt-004")).thenReturn(false);
        when(couchbaseReportRepository.findByMonthAndYear(8, 2026)).thenReturn(Optional.empty());
        when(monthlyReportRepository.save(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

        consumer.onMonthEndTrigger(event("evt-004"));

        verify(couchbaseReportRepository, never()).save(any());
        verify(processedEventRepository).save(any());
    }

}
