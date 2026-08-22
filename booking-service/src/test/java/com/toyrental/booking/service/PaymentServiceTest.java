package com.toyrental.booking.service;

import com.toyrental.booking.client.RazorpayClient;
import com.toyrental.booking.dto.RazorpayOrderResponse;
import com.toyrental.booking.dto.RazorpayWebhookRequest;
import com.toyrental.booking.entity.Booking;
import com.toyrental.booking.entity.BookingStatus;
import com.toyrental.booking.entity.Payment;
import com.toyrental.booking.entity.PaymentMethod;
import com.toyrental.booking.entity.PaymentStatus;
import com.toyrental.booking.entity.PaymentType;
import com.toyrental.booking.exception.PaymentFailedException;
import com.toyrental.booking.kafka.BookingEventProducer;
import com.toyrental.booking.kafka.PaymentEventProducer;
import com.toyrental.booking.repository.BookingRepository;
import com.toyrental.booking.repository.PaymentRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private RazorpayClient razorpayClient;
    @Mock
    private BookingEventProducer bookingEventProducer;
    @Mock
    private PaymentEventProducer paymentEventProducer;

    private SimpleMeterRegistry meterRegistry;
    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        paymentService = new PaymentService(paymentRepository, bookingRepository, razorpayClient,
                bookingEventProducer, paymentEventProducer, meterRegistry);
    }

    private Booking booking(String id, BookingStatus status) {
        return Booking.builder().id(id).toyId("toy-001").customerId("cust-001")
                .rentalAmount(BigDecimal.valueOf(300)).depositAmount(BigDecimal.valueOf(1000))
                .totalAmount(BigDecimal.valueOf(1300)).status(status).paymentStatus(PaymentStatus.PENDING).build();
    }

    private Payment payment(String id, String bookingId, PaymentType type, PaymentMethod method, PaymentStatus status) {
        return Payment.builder().id(id).bookingId(bookingId).customerId("cust-001")
                .amount(BigDecimal.valueOf(300)).type(type).method(method).status(status)
                .razorpayOrderId("order_mock123").build();
    }

    @Test
    void createPendingPaymentsCreatesRentalAndDepositSharingOneOrderId() {
        Booking booking = booking("bkg-1", BookingStatus.PENDING);
        when(razorpayClient.createOrder(any())).thenReturn(new RazorpayOrderResponse("order_mock123", "created", 130000, "INR", "bkg-1"));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<Payment> payments = paymentService.createPendingPayments(booking, PaymentMethod.UPI);

        assertThat(payments).hasSize(2);
        assertThat(payments).extracting(Payment::getType).containsExactlyInAnyOrder(PaymentType.RENTAL, PaymentType.DEPOSIT);
        assertThat(payments).allMatch(p -> p.getRazorpayOrderId().equals("order_mock123"));
        assertThat(payments).allMatch(p -> p.getStatus() == PaymentStatus.PENDING);
    }

    @Test
    void handleWebhookThrowsAndIncrementsFailedCounterWhenNoPendingPaymentFound() {
        when(paymentRepository.findByRazorpayOrderIdAndStatus("order_unknown", PaymentStatus.PENDING)).thenReturn(List.of());

        assertThrows(PaymentFailedException.class, () -> paymentService.handleWebhook(
                new RazorpayWebhookRequest("order_unknown", "pay_1", "sig_1")));

        assertThat(meterRegistry.get("payment.failed.total").tag("reason", "NO_PENDING_PAYMENT_FOUND").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void handleWebhookConfirmsBookingAndTagsSuccessCounterByMethod() {
        Payment rental = payment("pay-1", "bkg-1", PaymentType.RENTAL, PaymentMethod.UPI, PaymentStatus.PENDING);
        Payment deposit = payment("pay-2", "bkg-1", PaymentType.DEPOSIT, PaymentMethod.UPI, PaymentStatus.PENDING);
        when(paymentRepository.findByRazorpayOrderIdAndStatus("order_mock123", PaymentStatus.PENDING))
                .thenReturn(List.of(rental, deposit));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(bookingRepository.findById("bkg-1")).thenReturn(Optional.of(booking("bkg-1", BookingStatus.PENDING)));
        when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        paymentService.handleWebhook(new RazorpayWebhookRequest("order_mock123", "pay_test", "sig_test"));

        ArgumentCaptor<Booking> captor = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(captor.getValue().getPaymentStatus()).isEqualTo(PaymentStatus.SUCCESS);

        verify(bookingEventProducer).publishBookingConfirmed(any());
        verify(paymentEventProducer).publishPaymentSuccess(any());
        assertThat(meterRegistry.get("payment.success.total").tag("method", "UPI").counter().count()).isEqualTo(2.0);
    }

    @Test
    void handleWebhookConfirmsEveryDistinctBookingAmongMatchedPayments() {
        Payment paymentForBookingA = payment("pay-1", "bkg-a", PaymentType.RENTAL, PaymentMethod.UPI, PaymentStatus.PENDING);
        Payment paymentForBookingB = payment("pay-2", "bkg-b", PaymentType.RENTAL, PaymentMethod.UPI, PaymentStatus.PENDING);
        when(paymentRepository.findByRazorpayOrderIdAndStatus("order_mock123", PaymentStatus.PENDING))
                .thenReturn(List.of(paymentForBookingA, paymentForBookingB));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(bookingRepository.findById("bkg-a")).thenReturn(Optional.of(booking("bkg-a", BookingStatus.PENDING)));
        when(bookingRepository.findById("bkg-b")).thenReturn(Optional.of(booking("bkg-b", BookingStatus.PENDING)));
        when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        paymentService.handleWebhook(new RazorpayWebhookRequest("order_mock123", "pay_test", "sig_test"));

        verify(bookingEventProducer, org.mockito.Mockito.times(2)).publishBookingConfirmed(any());
    }

    @Test
    void refundDepositThrowsWhenNoSuccessfulDepositExists() {
        when(paymentRepository.findByBookingId("bkg-1")).thenReturn(List.of());

        assertThrows(PaymentFailedException.class, () -> paymentService.refundDeposit("bkg-1"));
    }

    @Test
    void refundDepositMarksTheDepositRowRefunded() {
        Payment deposit = payment("pay-2", "bkg-1", PaymentType.DEPOSIT, PaymentMethod.UPI, PaymentStatus.SUCCESS);
        when(paymentRepository.findByBookingId("bkg-1")).thenReturn(List.of(deposit));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        paymentService.refundDeposit("bkg-1");

        assertThat(deposit.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(deposit.getRefundId()).isNotNull();
        assertThat(deposit.getRefundedAt()).isNotNull();
    }

}
