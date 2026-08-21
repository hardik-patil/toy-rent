package com.toyrental.toy.kafka;

import com.toyrental.toy.entity.ProcessedEvent;
import com.toyrental.toy.repository.ProcessedEventRepository;
import com.toyrental.toy.service.AvailabilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingEventConsumerTest {

    @Mock
    private ProcessedEventRepository processedEventRepository;
    @Mock
    private AvailabilityService availabilityService;

    private BookingEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new BookingEventConsumer(processedEventRepository, availabilityService);
    }

    private BookingEventEnvelope confirmedEvent(String eventId) {
        return new BookingEventEnvelope(eventId, "BOOKING_CONFIRMED", "v1", Instant.now(), "corr-abc-123",
                "booking-service",
                new BookingEventPayload("bkg-00291", "toy-042", "cust-0091",
                        LocalDate.of(2025, 8, 1), LocalDate.of(2025, 8, 7)));
    }

    private BookingEventEnvelope cancelledEvent(String eventId) {
        return new BookingEventEnvelope(eventId, "BOOKING_CANCELLED", "v1", Instant.now(), "corr-abc-123",
                "booking-service",
                new BookingEventPayload("bkg-00291", "toy-042", "cust-0091",
                        LocalDate.of(2025, 8, 1), LocalDate.of(2025, 8, 7)));
    }

    @Test
    void onBookingConfirmedBlocksDatesAndRecordsEventAsProcessed() {
        when(processedEventRepository.existsById("evt-001")).thenReturn(false);

        consumer.onBookingConfirmed(confirmedEvent("evt-001"));

        verify(availabilityService).blockDates("toy-042", "bkg-00291",
                LocalDate.of(2025, 8, 1), LocalDate.of(2025, 8, 7));

        ArgumentCaptor<ProcessedEvent> captor = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEventRepository).save(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getEventId()).isEqualTo("evt-001");
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getEventType()).isEqualTo("BOOKING_CONFIRMED");
    }

    @Test
    void onBookingConfirmedSkipsAlreadyProcessedEvent() {
        when(processedEventRepository.existsById("evt-001")).thenReturn(true);

        consumer.onBookingConfirmed(confirmedEvent("evt-001"));

        verify(availabilityService, never()).blockDates(any(), any(), any(), any());
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    void onBookingCancelledReleasesDatesAndRecordsEventAsProcessed() {
        when(processedEventRepository.existsById("evt-002")).thenReturn(false);

        consumer.onBookingCancelled(cancelledEvent("evt-002"));

        verify(availabilityService).releaseDates("toy-042", "bkg-00291");
        verify(processedEventRepository).save(any());
    }

    @Test
    void onBookingCancelledSkipsAlreadyProcessedEvent() {
        when(processedEventRepository.existsById("evt-002")).thenReturn(true);

        consumer.onBookingCancelled(cancelledEvent("evt-002"));

        verify(availabilityService, never()).releaseDates(any(), any());
        verify(processedEventRepository, never()).save(any());
    }

}
