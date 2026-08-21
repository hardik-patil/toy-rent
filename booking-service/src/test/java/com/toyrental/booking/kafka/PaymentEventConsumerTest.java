package com.toyrental.booking.kafka;

import com.toyrental.booking.repository.ProcessedEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentEventConsumerTest {

    @Mock
    private ProcessedEventRepository processedEventRepository;

    private PaymentEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new PaymentEventConsumer(processedEventRepository);
    }

    private PaymentEventEnvelope event(String eventId, String eventType) {
        return new PaymentEventEnvelope(eventId, eventType, "v1", Instant.now(), "corr-1", "booking-service",
                new PaymentEventPayload("bkg-001", "cust-001", BigDecimal.valueOf(999), null));
    }

    @Test
    void onPaymentSuccessRecordsProcessedEvent() {
        when(processedEventRepository.existsById("evt-001")).thenReturn(false);

        consumer.onPaymentSuccess(event("evt-001", "PAYMENT_SUCCESS"));

        verify(processedEventRepository).save(any());
    }

    @Test
    void onPaymentSuccessSkipsAlreadyProcessedEvent() {
        when(processedEventRepository.existsById("evt-001")).thenReturn(true);

        consumer.onPaymentSuccess(event("evt-001", "PAYMENT_SUCCESS"));

        verify(processedEventRepository, never()).save(any());
    }

    @Test
    void onPaymentFailedRecordsProcessedEvent() {
        when(processedEventRepository.existsById("evt-002")).thenReturn(false);

        consumer.onPaymentFailed(event("evt-002", "PAYMENT_FAILED"));

        verify(processedEventRepository).save(any());
    }

}
