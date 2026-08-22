package com.toyrental.booking.service;

import com.toyrental.booking.client.RazorpayClient;
import com.toyrental.booking.dto.PaymentResponse;
import com.toyrental.booking.dto.RazorpayOrderRequest;
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
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.toyrental.booking.util.IdGenerator.shortId;

/**
 * Creates the Razorpay order via WireMock and the corresponding pending payment rows, and
 * processes the webhook callback. Deliberately makes the Razorpay call with no circuit breaker —
 * see RazorpayClient's Javadoc; that's an intentional Sprint 7 bottleneck, not an oversight here.
 */
@Slf4j
@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final BookingRepository bookingRepository;
    private final RazorpayClient razorpayClient;
    private final BookingEventProducer bookingEventProducer;
    private final PaymentEventProducer paymentEventProducer;
    private final MeterRegistry meterRegistry;

    public PaymentService(PaymentRepository paymentRepository, BookingRepository bookingRepository,
                           RazorpayClient razorpayClient, BookingEventProducer bookingEventProducer,
                           PaymentEventProducer paymentEventProducer, MeterRegistry meterRegistry) {
        this.paymentRepository = paymentRepository;
        this.bookingRepository = bookingRepository;
        this.razorpayClient = razorpayClient;
        this.bookingEventProducer = bookingEventProducer;
        this.paymentEventProducer = paymentEventProducer;
        this.meterRegistry = meterRegistry;
    }

    /**
     * CLAUDE.md's metric spec tags payment.success.total by method and payment.failed.total by
     * reason — both open-ended enough (an admin could add a new PaymentMethod; failure reasons
     * are free-form strings) that pre-building one Counter per value in the constructor isn't a
     * good fit. Counter.builder(...).register(registry) is idempotent by name+tags, so building
     * inline at each call site is the standard Micrometer pattern for this.
     */
    private void incrementPaymentSuccess(String method) {
        Counter.builder("payment.success.total")
                .tag("method", method)
                .description("Successful payments")
                .register(meterRegistry)
                .increment();
    }

    private void incrementPaymentFailed(String reason) {
        Counter.builder("payment.failed.total")
                .tag("reason", reason)
                .description("Failed payments")
                .register(meterRegistry)
                .increment();
    }

    /**
     * Called from BookingService.create() per CLAUDE.md's Booking Flow steps 5-6: create the
     * Razorpay order, then insert the payment row(s) as PENDING in the same transaction as the
     * booking insert. Rental and deposit are tracked as separate rows sharing one Razorpay order
     * (matches the PaymentType enum and lets the deposit be refunded independently later without
     * touching the rental charge).
     */
    @Transactional
    public List<Payment> createPendingPayments(Booking booking, PaymentMethod method) {
        RazorpayOrderResponse order = razorpayClient.createOrder(
                new RazorpayOrderRequest(booking.getTotalAmount(), "INR", booking.getId()));

        Payment rentalPayment = newPayment(booking, PaymentType.RENTAL, booking.getRentalAmount(), method, order.id());
        Payment depositPayment = newPayment(booking, PaymentType.DEPOSIT, booking.getDepositAmount(), method, order.id());

        // Reassign, don't discard the return value: Payment's id is manually assigned (no
        // @GeneratedValue), so save() routes through entityManager.merge() rather than persist()
        // and the passed-in instance is never mutated in place — see BookingService.create()'s
        // comment on the same pattern for the full explanation.
        rentalPayment = paymentRepository.save(rentalPayment);
        depositPayment = paymentRepository.save(depositPayment);
        log.info("Created pending payments bookingId={} razorpayOrderId={}", booking.getId(), order.id());
        return List.of(rentalPayment, depositPayment);
    }

    private Payment newPayment(Booking booking, PaymentType type, java.math.BigDecimal amount,
                                PaymentMethod method, String razorpayOrderId) {
        return Payment.builder()
                .id(shortId("pay"))
                .bookingId(booking.getId())
                .customerId(booking.getCustomerId())
                .amount(amount)
                .type(type)
                .method(method)
                .status(PaymentStatus.PENDING)
                .razorpayOrderId(razorpayOrderId)
                .build();
    }

    @Transactional(readOnly = true)
    public List<Payment> findByBookingId(String bookingId) {
        return paymentRepository.findByBookingId(bookingId);
    }

    /**
     * BookingService.create() already creates the pending payment rows + Razorpay order per
     * CLAUDE.md's Booking Flow steps 5-6, so under normal operation this just returns the
     * existing PENDING payment for a retry (e.g. the customer's checkout session expired before
     * they paid). Only bookings without any pending payment (already paid, or none exist) are
     * rejected.
     */
    @Transactional(readOnly = true)
    public PaymentResponse initiate(String bookingId, String customerId) {
        Payment pending = paymentRepository.findByBookingId(bookingId).stream()
                .filter(p -> p.getCustomerId().equals(customerId))
                .filter(p -> p.getStatus() == PaymentStatus.PENDING)
                .findFirst()
                .orElseThrow(() -> new PaymentFailedException(
                        "No pending payment to initiate for booking " + bookingId));
        return PaymentResponse.from(pending);
    }

    @Transactional(readOnly = true)
    public PaymentResponse getById(String paymentId) {
        return PaymentResponse.from(paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentFailedException("Payment not found: " + paymentId)));
    }

    /**
     * CLAUDE.md's webhook step 1 says "verify razorpay_signature" — with no real Razorpay
     * webhook secret in this mocked setup, verification here means correlating the callback to a
     * genuinely pending payment for the order it claims to settle, rather than a cryptographic
     * HMAC check.
     */
    @Transactional
    public void handleWebhook(RazorpayWebhookRequest webhook) {
        List<Payment> payments = paymentRepository.findByRazorpayOrderIdAndStatus(
                webhook.razorpayOrderId(), PaymentStatus.PENDING);

        if (payments.isEmpty()) {
            incrementPaymentFailed("NO_PENDING_PAYMENT_FOUND");
            throw new PaymentFailedException("No pending payment found for razorpay_order_id " + webhook.razorpayOrderId());
        }

        for (Payment payment : payments) {
            payment.setStatus(PaymentStatus.SUCCESS);
            payment.setRazorpayPaymentId(webhook.razorpayPaymentId());
            payment.setRazorpaySignature(webhook.razorpaySignature());
            paymentRepository.save(payment);
            incrementPaymentSuccess(payment.getMethod().name());
        }

        // A razorpay_order_id maps 1:1 to a single booking under real Razorpay (every order
        // creation call returns a fresh id); the WireMock stub returns the same static order id
        // for every order, so distinct bookings can end up sharing one during local testing.
        // Confirming every distinct booking found among the matched payments — not just the
        // first — keeps that scenario from leaving a booking's payments SUCCESS while its own
        // status stays PENDING, which is exactly the inconsistency a single-booking assumption
        // produced here (caught via live testing, not by inspection).
        List<String> bookingIds = payments.stream().map(Payment::getBookingId).distinct().toList();
        for (String bookingId : bookingIds) {
            Booking booking = bookingRepository.findById(bookingId)
                    .orElseThrow(() -> new PaymentFailedException("Booking not found for payment: " + bookingId));
            booking.setStatus(BookingStatus.CONFIRMED);
            booking.setPaymentStatus(PaymentStatus.SUCCESS);
            bookingRepository.save(booking);

            bookingEventProducer.publishBookingConfirmed(booking);
            paymentEventProducer.publishPaymentSuccess(booking);
            log.info("Payment confirmed bookingId={} razorpayOrderId={}", bookingId, webhook.razorpayOrderId());
        }
    }

    @Transactional
    public PaymentResponse refundDeposit(String bookingId) {
        Payment deposit = paymentRepository.findByBookingId(bookingId).stream()
                .filter(p -> p.getType() == PaymentType.DEPOSIT)
                .filter(p -> p.getStatus() == PaymentStatus.SUCCESS)
                .findFirst()
                .orElseThrow(() -> new PaymentFailedException(
                        "No successful deposit payment found for booking " + bookingId));

        deposit.setStatus(PaymentStatus.REFUNDED);
        deposit.setRefundId("rfnd-" + UUID.randomUUID());
        deposit.setRefundedAt(LocalDateTime.now());
        Payment saved = paymentRepository.save(deposit);
        log.info("Refunded deposit bookingId={} paymentId={}", bookingId, saved.getId());
        return PaymentResponse.from(saved);
    }

}
