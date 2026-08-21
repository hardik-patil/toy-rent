package com.toyrental.booking.service;

import com.toyrental.booking.client.ToyServiceClient;
import com.toyrental.booking.dto.BookingCancelRequest;
import com.toyrental.booking.dto.BookingRequest;
import com.toyrental.booking.dto.BookingResponse;
import com.toyrental.booking.dto.ToyAvailabilityResponse;
import com.toyrental.booking.dto.ToyDetailResponse;
import com.toyrental.booking.entity.Booking;
import com.toyrental.booking.entity.BookingStatus;
import com.toyrental.booking.entity.Payment;
import com.toyrental.booking.entity.PaymentStatus;
import com.toyrental.booking.entity.RentalType;
import com.toyrental.booking.exception.BookingNotFoundException;
import com.toyrental.booking.exception.BookingStateConflictException;
import com.toyrental.booking.exception.ToyNotAvailableException;
import com.toyrental.booking.kafka.BookingEventProducer;
import com.toyrental.booking.repository.BookingRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private ToyServiceClient toyServiceClient;
    @Mock
    private PaymentService paymentService;
    @Mock
    private CustomerService customerService;
    @Mock
    private PdfGeneratorService pdfGeneratorService;
    @Mock
    private BookingEventProducer bookingEventProducer;

    private BookingService bookingService;

    private static final String CUSTOMER_ID = "cust-0001";
    private static final String TOY_ID = "toy-001";

    @BeforeEach
    void setUp() {
        bookingService = new BookingService(bookingRepository, toyServiceClient, paymentService,
                customerService, pdfGeneratorService, bookingEventProducer, new SimpleMeterRegistry());
    }

    private BookingRequest weeklyRequest() {
        return new BookingRequest(TOY_ID, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 7),
                RentalType.WEEKLY, "B-204", "Neelkanth Heights", "Kharghar", "Navi Mumbai", "410210");
    }

    private ToyDetailResponse activeToy() {
        return new ToyDetailResponse(TOY_ID, "LEGO Technic", "AVAILABLE",
                BigDecimal.valueOf(299), BigDecimal.valueOf(899), BigDecimal.valueOf(1500), true);
    }

    @Test
    void createThrowsWhenToyDoesNotExist() {
        when(toyServiceClient.getToy(TOY_ID)).thenReturn(null);

        assertThrows(ToyNotAvailableException.class, () -> bookingService.create(CUSTOMER_ID, weeklyRequest()));
        verify(bookingRepository, never()).saveAndFlush(any());
    }

    @Test
    void createThrowsWhenToyNotAvailableForRange() {
        when(toyServiceClient.getToy(TOY_ID)).thenReturn(activeToy());
        when(toyServiceClient.checkAvailability(eq(TOY_ID), anyString(), anyString()))
                .thenReturn(new ToyAvailabilityResponse(TOY_ID, "LEGO Technic", "AVAILABLE", false));

        assertThrows(ToyNotAvailableException.class, () -> bookingService.create(CUSTOMER_ID, weeklyRequest()));
        verify(bookingRepository, never()).saveAndFlush(any());
    }

    @Test
    void createThrowsWhenLocalOverlapFoundAfterInsert() {
        when(toyServiceClient.getToy(TOY_ID)).thenReturn(activeToy());
        when(toyServiceClient.checkAvailability(eq(TOY_ID), anyString(), anyString()))
                .thenReturn(new ToyAvailabilityResponse(TOY_ID, "LEGO Technic", "AVAILABLE", true));
        when(bookingRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(bookingRepository.findOverlapping(eq(TOY_ID), any(), any(), any(), anyString()))
                .thenReturn(List.of(Booking.builder().id("bkg-other").build()));

        assertThrows(ToyNotAvailableException.class, () -> bookingService.create(CUSTOMER_ID, weeklyRequest()));
        verify(paymentService, never()).createPendingPayments(any(), any());
    }

    @Test
    void createSucceedsAndComputesWeeklyRentalAmount() {
        when(toyServiceClient.getToy(TOY_ID)).thenReturn(activeToy());
        when(toyServiceClient.checkAvailability(eq(TOY_ID), anyString(), anyString()))
                .thenReturn(new ToyAvailabilityResponse(TOY_ID, "LEGO Technic", "AVAILABLE", true));
        when(bookingRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(bookingRepository.findOverlapping(eq(TOY_ID), any(), any(), any(), anyString()))
                .thenReturn(List.of());

        Payment payment = Payment.builder().razorpayOrderId("order_mock123").build();
        when(paymentService.createPendingPayments(any(), any())).thenReturn(List.of(payment, payment));

        BookingResponse response = bookingService.create(CUSTOMER_ID, weeklyRequest());

        // 7-day range = exactly 1 week -> 1 * weeklyPrice(299)
        assertThat(response.rentalAmount()).isEqualByComparingTo("299");
        assertThat(response.depositAmount()).isEqualByComparingTo("1500");
        assertThat(response.totalAmount()).isEqualByComparingTo("1799");
        assertThat(response.razorpayOrderId()).isEqualTo("order_mock123");

        ArgumentCaptor<Booking> captor = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(captor.getValue().getCustomerId()).isEqualTo(CUSTOMER_ID);
    }

    @Test
    void cancelThrowsWhenBookingNotFound() {
        when(bookingRepository.findByIdAndCustomerId("bkg-x", CUSTOMER_ID)).thenReturn(Optional.empty());

        assertThrows(BookingNotFoundException.class,
                () -> bookingService.cancel("bkg-x", CUSTOMER_ID, new BookingCancelRequest("changed my mind")));
    }

    @Test
    void cancelThrowsWhenAlreadyCancelled() {
        Booking booking = Booking.builder().id("bkg-1").customerId(CUSTOMER_ID).status(BookingStatus.CANCELLED).build();
        when(bookingRepository.findByIdAndCustomerId("bkg-1", CUSTOMER_ID)).thenReturn(Optional.of(booking));

        assertThrows(BookingStateConflictException.class,
                () -> bookingService.cancel("bkg-1", CUSTOMER_ID, new BookingCancelRequest("again")));
    }

    @Test
    void cancelPublishesBookingCancelledOnlyWhenBookingWasHoldingTheToy() {
        Booking pendingBooking = Booking.builder().id("bkg-pending").customerId(CUSTOMER_ID)
                .status(BookingStatus.PENDING).paymentStatus(PaymentStatus.PENDING).build();
        when(bookingRepository.findByIdAndCustomerId("bkg-pending", CUSTOMER_ID)).thenReturn(Optional.of(pendingBooking));

        bookingService.cancel("bkg-pending", CUSTOMER_ID, new BookingCancelRequest("reason"));

        verify(bookingEventProducer, never()).publishBookingCancelled(any());
        assertThat(pendingBooking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
    }

    @Test
    void cancelPublishesBookingCancelledWhenBookingWasConfirmed() {
        Booking confirmedBooking = Booking.builder().id("bkg-confirmed").customerId(CUSTOMER_ID)
                .status(BookingStatus.CONFIRMED).paymentStatus(PaymentStatus.SUCCESS).build();
        when(bookingRepository.findByIdAndCustomerId("bkg-confirmed", CUSTOMER_ID)).thenReturn(Optional.of(confirmedBooking));

        bookingService.cancel("bkg-confirmed", CUSTOMER_ID, new BookingCancelRequest("reason"));

        verify(bookingEventProducer).publishBookingCancelled(confirmedBooking);
    }

}
