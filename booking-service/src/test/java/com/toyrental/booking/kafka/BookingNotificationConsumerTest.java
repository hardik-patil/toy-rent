package com.toyrental.booking.kafka;

import com.toyrental.booking.entity.Customer;
import com.toyrental.booking.repository.ProcessedEventRepository;
import com.toyrental.booking.service.CustomerService;
import com.toyrental.booking.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingNotificationConsumerTest {

    @Mock
    private ProcessedEventRepository processedEventRepository;
    @Mock
    private CustomerService customerService;
    @Mock
    private NotificationService notificationService;

    private BookingNotificationConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new BookingNotificationConsumer(processedEventRepository, customerService, notificationService);
    }

    private BookingEventEnvelope event(String eventId, String eventType) {
        return new BookingEventEnvelope(eventId, eventType, "v1", Instant.now(), "corr-1", "booking-service",
                new BookingEventPayload("bkg-001", "toy-001", "cust-001",
                        LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 7)));
    }

    @Test
    void onBookingConfirmedSendsConfirmationNotification() {
        when(processedEventRepository.existsById("evt-001")).thenReturn(false);
        Customer customer = Customer.builder().id("cust-001").phone("9821012345").build();
        when(customerService.requireCustomer("cust-001")).thenReturn(customer);

        consumer.onBookingConfirmed(event("evt-001", "BOOKING_CONFIRMED"));

        verify(notificationService).sendBookingConfirmation(eq(customer), eq("bkg-001"), anyString());
        verify(processedEventRepository).save(any());
    }

    @Test
    void onBookingConfirmedSkipsAlreadyProcessedEvent() {
        when(processedEventRepository.existsById("evt-001")).thenReturn(true);

        consumer.onBookingConfirmed(event("evt-001", "BOOKING_CONFIRMED"));

        verify(customerService, never()).requireCustomer(anyString());
        verify(notificationService, never()).sendBookingConfirmation(any(), anyString(), anyString());
    }

    @Test
    void onBookingCancelledSendsCancellationNotification() {
        when(processedEventRepository.existsById("evt-002")).thenReturn(false);
        Customer customer = Customer.builder().id("cust-001").phone("9821012345").build();
        when(customerService.requireCustomer("cust-001")).thenReturn(customer);

        consumer.onBookingCancelled(event("evt-002", "BOOKING_CANCELLED"));

        verify(notificationService).sendBookingCancellation(eq(customer), eq("bkg-001"), anyString());
    }

    @Test
    void onBookingOverdueSendsOverdueReminder() {
        when(processedEventRepository.existsById("evt-003")).thenReturn(false);
        Customer customer = Customer.builder().id("cust-001").phone("9821012345").build();
        when(customerService.requireCustomer("cust-001")).thenReturn(customer);

        consumer.onBookingOverdue(event("evt-003", "BOOKING_OVERDUE"));

        verify(notificationService).sendOverdueReminder(eq(customer), eq("bkg-001"), anyString());
    }

}
