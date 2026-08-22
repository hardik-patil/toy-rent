package com.toyrental.booking.service;

import com.toyrental.booking.client.ToyServiceClient;
import com.toyrental.booking.dto.ReportAggregate;
import com.toyrental.booking.dto.ToyDetailResponse;
import com.toyrental.booking.entity.Booking;
import com.toyrental.booking.entity.BookingStatus;
import com.toyrental.booking.entity.RentalType;
import com.toyrental.booking.repository.BookingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private ToyServiceClient toyServiceClient;

    private ReportService reportService;

    @BeforeEach
    void setUp() {
        reportService = new ReportService(bookingRepository, toyServiceClient);
    }

    private Booking booking(String id, String toyId, int startDay, BigDecimal rental, BigDecimal deposit, BookingStatus status) {
        return Booking.builder().id(id).toyId(toyId)
                .startDate(LocalDate.of(2026, 8, startDay)).endDate(LocalDate.of(2026, 8, startDay + 6))
                .rentalType(RentalType.WEEKLY).rentalAmount(rental).depositAmount(deposit)
                .totalAmount(rental.add(deposit)).status(status).build();
    }

    @Test
    void aggregatesRevenueAndCountsAcrossMaterializedBookings() {
        List<Booking> bookings = List.of(
                booking("bkg-1", "toy-001", 1, BigDecimal.valueOf(300), BigDecimal.valueOf(1000), BookingStatus.CONFIRMED),
                booking("bkg-2", "toy-001", 8, BigDecimal.valueOf(300), BigDecimal.valueOf(1000), BookingStatus.RETURNED),
                booking("bkg-3", "toy-002", 15, BigDecimal.valueOf(200), BigDecimal.valueOf(500), BookingStatus.ACTIVE)
        );
        when(bookingRepository.findByStartDateBetweenAndStatusIn(
                eq(LocalDate.of(2026, 8, 1)), eq(LocalDate.of(2026, 8, 31)), any()))
                .thenReturn(bookings);
        when(toyServiceClient.getToy("toy-001")).thenReturn(
                new ToyDetailResponse("toy-001", "LEGO Technic", "AVAILABLE", BigDecimal.valueOf(300), BigDecimal.valueOf(900), BigDecimal.valueOf(1000), true));

        ReportAggregate aggregate = reportService.aggregate(8, 2026);

        assertThat(aggregate.totalBookings()).isEqualTo(3);
        assertThat(aggregate.totalRevenue()).isEqualByComparingTo("800");
        assertThat(aggregate.totalDeposits()).isEqualByComparingTo("2500");
        assertThat(aggregate.pendingReturns()).isEqualTo(1); // only the ACTIVE one
        assertThat(aggregate.topToy().toyId()).isEqualTo("toy-001");
        assertThat(aggregate.topToy().name()).isEqualTo("LEGO Technic");
        assertThat(aggregate.topToy().rentals()).isEqualTo(2);
        assertThat(aggregate.revenueByWeek()).hasSize(3);
    }

    @Test
    void returnsZeroedAggregateWhenNoBookingsMatch() {
        when(bookingRepository.findByStartDateBetweenAndStatusIn(any(), any(), any())).thenReturn(List.of());

        ReportAggregate aggregate = reportService.aggregate(8, 2026);

        assertThat(aggregate.totalBookings()).isZero();
        assertThat(aggregate.totalRevenue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(aggregate.topToy()).isNull();
        assertThat(aggregate.revenueByWeek()).isEmpty();
    }

    @Test
    void fallsBackToToyIdWhenToyServiceLookupFails() {
        List<Booking> bookings = List.of(booking("bkg-1", "toy-999", 1, BigDecimal.valueOf(100), BigDecimal.valueOf(200), BookingStatus.CONFIRMED));
        when(bookingRepository.findByStartDateBetweenAndStatusIn(any(), any(), any())).thenReturn(bookings);
        when(toyServiceClient.getToy("toy-999")).thenThrow(new RuntimeException("toy-service unavailable"));

        ReportAggregate aggregate = reportService.aggregate(8, 2026);

        assertThat(aggregate.topToy().name()).isEqualTo("toy-999");
    }

}
