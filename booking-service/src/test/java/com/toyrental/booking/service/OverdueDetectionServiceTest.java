package com.toyrental.booking.service;

import com.toyrental.booking.entity.Booking;
import com.toyrental.booking.entity.BookingStatus;
import com.toyrental.booking.kafka.BookingEventProducer;
import com.toyrental.booking.repository.BookingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OverdueDetectionServiceTest {

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private BookingEventProducer bookingEventProducer;

    private OverdueDetectionService overdueDetectionService;

    @BeforeEach
    void setUp() {
        overdueDetectionService = new OverdueDetectionService(bookingRepository, bookingEventProducer);
    }

    @Test
    void doesNothingWhenNoBookingsAreOverdue() {
        when(bookingRepository.findByStatusAndEndDateBefore(eq(BookingStatus.ACTIVE), any(), any(Pageable.class)))
                .thenReturn(Page.empty());

        overdueDetectionService.detectAndPublishOverdueBookings();

        verify(bookingRepository, never()).save(any());
        verify(bookingEventProducer, never()).publishBookingOverdue(any());
    }

    @Test
    void flipsOverdueBookingsAndPublishesEvents() {
        Booking overdue1 = Booking.builder().id("bkg-1").status(BookingStatus.ACTIVE)
                .endDate(LocalDate.now().minusDays(2)).build();
        Booking overdue2 = Booking.builder().id("bkg-2").status(BookingStatus.ACTIVE)
                .endDate(LocalDate.now().minusDays(5)).build();
        when(bookingRepository.findByStatusAndEndDateBefore(eq(BookingStatus.ACTIVE), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(overdue1, overdue2)));

        overdueDetectionService.detectAndPublishOverdueBookings();

        assertThat(overdue1.getStatus()).isEqualTo(BookingStatus.OVERDUE);
        assertThat(overdue2.getStatus()).isEqualTo(BookingStatus.OVERDUE);
        verify(bookingEventProducer).publishBookingOverdue(overdue1);
        verify(bookingEventProducer).publishBookingOverdue(overdue2);
    }

}
