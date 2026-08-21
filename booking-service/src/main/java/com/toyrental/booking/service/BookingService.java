package com.toyrental.booking.service;

import com.toyrental.booking.client.ToyServiceClient;
import com.toyrental.booking.dto.BookingCancelRequest;
import com.toyrental.booking.dto.BookingExtendRequest;
import com.toyrental.booking.dto.BookingRequest;
import com.toyrental.booking.dto.BookingResponse;
import com.toyrental.booking.dto.BookingReturnRequest;
import com.toyrental.booking.dto.PagedResponse;
import com.toyrental.booking.dto.ToyAvailabilityResponse;
import com.toyrental.booking.dto.ToyDetailResponse;
import com.toyrental.booking.entity.Booking;
import com.toyrental.booking.entity.BookingStatus;
import com.toyrental.booking.entity.Payment;
import com.toyrental.booking.entity.PaymentMethod;
import com.toyrental.booking.entity.PaymentStatus;
import com.toyrental.booking.entity.RentalType;
import com.toyrental.booking.exception.BookingNotFoundException;
import com.toyrental.booking.exception.BookingStateConflictException;
import com.toyrental.booking.exception.InvalidBookingRequestException;
import com.toyrental.booking.exception.ToyNotAvailableException;
import com.toyrental.booking.kafka.BookingEventProducer;
import com.toyrental.booking.repository.BookingRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static com.toyrental.booking.util.IdGenerator.shortId;

@Slf4j
@Service
public class BookingService {

    /** Statuses that still hold a toy — used both for the local overlap lock and admin queries. */
    private static final List<BookingStatus> ACTIVE_STATUSES =
            List.of(BookingStatus.PENDING, BookingStatus.CONFIRMED, BookingStatus.ACTIVE);

    private final BookingRepository bookingRepository;
    private final ToyServiceClient toyServiceClient;
    private final PaymentService paymentService;
    private final CustomerService customerService;
    private final PdfGeneratorService pdfGeneratorService;
    private final BookingEventProducer bookingEventProducer;
    private final Counter bookingConflictCounter;
    private final Counter weeklyBookingCreatedCounter;
    private final Counter monthlyBookingCreatedCounter;

    public BookingService(BookingRepository bookingRepository, ToyServiceClient toyServiceClient,
                           PaymentService paymentService, CustomerService customerService,
                           PdfGeneratorService pdfGeneratorService, BookingEventProducer bookingEventProducer,
                           MeterRegistry meterRegistry) {
        this.bookingRepository = bookingRepository;
        this.toyServiceClient = toyServiceClient;
        this.paymentService = paymentService;
        this.customerService = customerService;
        this.pdfGeneratorService = pdfGeneratorService;
        this.bookingEventProducer = bookingEventProducer;
        this.bookingConflictCounter = Counter.builder("booking.conflict.total")
                .description("Booking conflicts — toy not available")
                .register(meterRegistry);
        this.weeklyBookingCreatedCounter = Counter.builder("booking.created.total")
                .tag("rental_type", RentalType.WEEKLY.name())
                .description("Total bookings created")
                .register(meterRegistry);
        this.monthlyBookingCreatedCounter = Counter.builder("booking.created.total")
                .tag("rental_type", RentalType.MONTHLY.name())
                .description("Total bookings created")
                .register(meterRegistry);
    }

    /**
     * Follows CLAUDE.md's "Booking Flow — Critical Logic" exactly: verify the toy exists, check
     * toy-service's Couchbase-backed availability, insert the booking as PENDING, then take a
     * pessimistic lock over this service's own bookings table to close the race a second
     * concurrent request could otherwise win between the availability check and the insert.
     * Creating the Razorpay order + pending payment rows (steps 5-6) happens inside the same
     * transaction, so a rollback on conflict also leaves no orphaned payment.
     */
    @Transactional
    public BookingResponse create(String customerId, BookingRequest request) {
        if (!request.endDate().isAfter(request.startDate()) && !request.endDate().isEqual(request.startDate())) {
            throw new InvalidBookingRequestException("endDate must be on or after startDate");
        }

        ToyDetailResponse toy = toyServiceClient.getToy(request.toyId());
        if (toy == null || !toy.active()) {
            throw new ToyNotAvailableException("Toy " + request.toyId() + " does not exist or is inactive");
        }

        ToyAvailabilityResponse availability = toyServiceClient.checkAvailability(
                request.toyId(), request.startDate().toString(), request.endDate().toString());
        if (!Boolean.TRUE.equals(availability.available())) {
            bookingConflictCounter.increment();
            throw new ToyNotAvailableException("Toy " + request.toyId() + " is not available for "
                    + request.startDate() + " to " + request.endDate());
        }

        BigDecimal rentalAmount = computeRentalAmount(toy, request.rentalType(), request.startDate(), request.endDate());
        BigDecimal depositAmount = toy.depositAmount();

        Booking booking = Booking.builder()
                .id(shortId("bkg"))
                .toyId(request.toyId())
                .customerId(customerId)
                .startDate(request.startDate())
                .endDate(request.endDate())
                .rentalType(request.rentalType())
                .rentalAmount(rentalAmount)
                .depositAmount(depositAmount)
                .totalAmount(rentalAmount.add(depositAmount))
                .status(BookingStatus.PENDING)
                .paymentStatus(PaymentStatus.PENDING)
                .deliveryFlat(request.deliveryFlat())
                .deliveryBuilding(request.deliveryBuilding())
                .deliveryArea(request.deliveryArea())
                .deliveryCity(request.deliveryCity())
                .deliveryPincode(request.deliveryPincode())
                .build();
        // saveAndFlush, not save: the pessimistic-lock overlap query right below needs this
        // booking's INSERT to have actually reached Postgres to be a meaningful check at all, and
        // createdAt (read via BookingResponse.from at the end of this method) is only populated by
        // Hibernate's @CreationTimestamp generator once the INSERT is flushed. Reassigning is
        // required, not optional: this entity has a manually-assigned (non-@GeneratedValue) id, so
        // Spring Data's isNew() check sees a non-null id and routes save()/saveAndFlush() through
        // entityManager.merge() rather than persist() — merge() returns a different managed
        // instance and leaves the object passed in untouched, so re-reading the original `booking`
        // variable below would still see createdAt=null.
        booking = bookingRepository.saveAndFlush(booking);

        List<Booking> overlapping = bookingRepository.findOverlapping(
                request.toyId(), request.startDate(), request.endDate(), ACTIVE_STATUSES, booking.getId());
        if (!overlapping.isEmpty()) {
            bookingConflictCounter.increment();
            throw new ToyNotAvailableException("Toy " + request.toyId() + " was just booked by another request "
                    + "for an overlapping date range");
        }

        List<Payment> payments = paymentService.createPendingPayments(booking, PaymentMethod.UPI);
        String razorpayOrderId = payments.get(0).getRazorpayOrderId();

        (request.rentalType() == RentalType.WEEKLY ? weeklyBookingCreatedCounter : monthlyBookingCreatedCounter).increment();

        log.info("Created booking id={} toyId={} customerId={} status=PENDING", booking.getId(), request.toyId(), customerId);
        return BookingResponse.from(booking, razorpayOrderId);
    }

    @Transactional(readOnly = true)
    public BookingResponse getByIdForCustomer(String bookingId, String customerId) {
        Booking booking = bookingRepository.findByIdAndCustomerId(bookingId, customerId)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));
        return BookingResponse.from(booking, null);
    }

    @Transactional(readOnly = true)
    public Booking requireBooking(String bookingId) {
        return bookingRepository.findById(bookingId)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));
    }

    @Transactional(readOnly = true)
    public byte[] generateReceipt(String bookingId, String customerId) {
        Booking booking = bookingRepository.findByIdAndCustomerId(bookingId, customerId)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));
        Payment payment = paymentService.findByBookingId(bookingId).stream()
                .filter(p -> p.getStatus() == PaymentStatus.SUCCESS)
                .findFirst()
                .orElse(null);
        return pdfGeneratorService.generateBookingReceipt(booking, customerService.requireCustomer(customerId), payment);
    }

    @Transactional(readOnly = true)
    public PagedResponse<BookingResponse> getMyBookings(String customerId, Pageable pageable) {
        Page<BookingResponse> page = bookingRepository.findByCustomerId(customerId, pageable)
                .map(b -> BookingResponse.from(b, null));
        return PagedResponse.from(page);
    }

    @Transactional
    public BookingResponse cancel(String bookingId, String customerId, BookingCancelRequest request) {
        Booking booking = bookingRepository.findByIdAndCustomerId(bookingId, customerId)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));
        return doCancel(booking, "CUSTOMER", request.reason());
    }

    @Transactional
    public BookingResponse adminCancel(String bookingId, String reason) {
        return doCancel(requireBooking(bookingId), "ADMIN", reason);
    }

    private BookingResponse doCancel(Booking booking, String cancelledBy, String reason) {
        if (booking.getStatus() == BookingStatus.CANCELLED || booking.getStatus() == BookingStatus.RETURNED) {
            throw new BookingStateConflictException("Booking " + booking.getId() + " is already " + booking.getStatus());
        }

        boolean wasHoldingToy = booking.getStatus() == BookingStatus.CONFIRMED || booking.getStatus() == BookingStatus.ACTIVE;
        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancelledBy(cancelledBy);
        booking.setCancelReason(reason);
        booking.setCancelledAt(LocalDateTime.now());
        bookingRepository.save(booking);

        if (wasHoldingToy) {
            bookingEventProducer.publishBookingCancelled(booking);
        }
        log.info("Cancelled booking id={} by={} wasHoldingToy={}", booking.getId(), cancelledBy, wasHoldingToy);
        return BookingResponse.from(booking, null);
    }

    /**
     * Extending re-publishes booking.confirmed with the new date range under the same bookingId —
     * toy-service's BookingEventConsumer treats a block as idempotent-per-bookingId and replaces
     * the prior range rather than adding a second one, so this reuses that behavior instead of
     * needing a dedicated "extend" event type.
     */
    @Transactional
    public BookingResponse extend(String bookingId, String customerId, BookingExtendRequest request) {
        Booking booking = bookingRepository.findByIdAndCustomerId(bookingId, customerId)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));

        if (booking.getStatus() != BookingStatus.CONFIRMED && booking.getStatus() != BookingStatus.ACTIVE) {
            throw new BookingStateConflictException("Booking " + bookingId + " cannot be extended in status " + booking.getStatus());
        }
        if (!request.newEndDate().isAfter(booking.getEndDate())) {
            throw new InvalidBookingRequestException("newEndDate must be after the current endDate");
        }

        ToyAvailabilityResponse availability = toyServiceClient.checkAvailability(booking.getToyId(),
                booking.getEndDate().plusDays(1).toString(), request.newEndDate().toString());
        if (!Boolean.TRUE.equals(availability.available())) {
            bookingConflictCounter.increment();
            throw new ToyNotAvailableException("Toy " + booking.getToyId() + " is not available for the extended range");
        }

        List<Booking> overlapping = bookingRepository.findOverlapping(
                booking.getToyId(), booking.getStartDate(), request.newEndDate(), ACTIVE_STATUSES, booking.getId());
        if (!overlapping.isEmpty()) {
            bookingConflictCounter.increment();
            throw new ToyNotAvailableException("Toy " + booking.getToyId() + " was just booked by another request");
        }

        ToyDetailResponse toy = toyServiceClient.getToy(booking.getToyId());
        BigDecimal newRentalAmount = computeRentalAmount(toy, booking.getRentalType(), booking.getStartDate(), request.newEndDate());
        booking.setEndDate(request.newEndDate());
        booking.setRentalAmount(newRentalAmount);
        booking.setTotalAmount(newRentalAmount.add(booking.getDepositAmount()));
        bookingRepository.save(booking);

        bookingEventProducer.publishBookingConfirmed(booking);
        log.info("Extended booking id={} newEndDate={}", bookingId, request.newEndDate());
        return BookingResponse.from(booking, null);
    }

    @Transactional
    public BookingResponse markReturned(String bookingId, BookingReturnRequest request) {
        Booking booking = requireBooking(bookingId);
        booking.setStatus(BookingStatus.RETURNED);
        booking.setReturnedAt(LocalDateTime.now());
        booking.setReturnCondition(request.condition());
        booking.setDamageNotes(request.damageNotes());
        bookingRepository.save(booking);
        log.info("Marked booking id={} as RETURNED condition={}", bookingId, request.condition());
        return BookingResponse.from(booking, null);
    }

    @Transactional
    public BookingResponse confirmManually(String bookingId) {
        Booking booking = requireBooking(bookingId);
        if (booking.getStatus() != BookingStatus.PENDING) {
            throw new BookingStateConflictException("Booking " + bookingId + " is not PENDING (status=" + booking.getStatus() + ")");
        }
        booking.setStatus(BookingStatus.CONFIRMED);
        booking.setPaymentStatus(PaymentStatus.SUCCESS);
        bookingRepository.save(booking);
        bookingEventProducer.publishBookingConfirmed(booking);
        log.info("Manually confirmed booking id={}", bookingId);
        return BookingResponse.from(booking, null);
    }

    @Transactional(readOnly = true)
    public PagedResponse<BookingResponse> adminBrowse(BookingStatus status, Pageable pageable) {
        return PagedResponse.from(bookingRepository.browse(status, pageable).map(b -> BookingResponse.from(b, null)));
    }

    /**
     * "Today" here uses the wall clock rather than a LogicalDateService — unlike toy-service,
     * booking-service has no LogicalDateService of its own and no logical-date Couchbase bucket
     * declared in its config (that bucket is toy-service-owned per CLAUDE.md's database ownership
     * rules). Building cross-service logical-date access is out of scope for this sprint; flagged
     * here as a deliberate, scoped exception to the "never call LocalDate.now()" rule rather than
     * a missed case of it.
     */
    @Transactional(readOnly = true)
    public PagedResponse<BookingResponse> todaysDeliveries(Pageable pageable) {
        return PagedResponse.from(bookingRepository
                .findByStatusAndStartDate(BookingStatus.CONFIRMED, LocalDate.now(), pageable)
                .map(b -> BookingResponse.from(b, null)));
    }

    @Transactional(readOnly = true)
    public PagedResponse<BookingResponse> todaysPickups(Pageable pageable) {
        return PagedResponse.from(bookingRepository
                .findByStatusAndEndDate(BookingStatus.ACTIVE, LocalDate.now(), pageable)
                .map(b -> BookingResponse.from(b, null)));
    }

    @Transactional(readOnly = true)
    public PagedResponse<BookingResponse> overdue(Pageable pageable) {
        return PagedResponse.from(bookingRepository
                .findByStatusAndEndDateBefore(BookingStatus.ACTIVE, LocalDate.now(), pageable)
                .map(b -> BookingResponse.from(b, null)));
    }

    private BigDecimal computeRentalAmount(ToyDetailResponse toy, RentalType rentalType, LocalDate start, LocalDate end) {
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if (rentalType == RentalType.WEEKLY) {
            long weeks = (long) Math.ceil(days / 7.0);
            return toy.weeklyPrice().multiply(BigDecimal.valueOf(weeks));
        }
        long months = (long) Math.ceil(days / 30.0);
        return toy.monthlyPrice().multiply(BigDecimal.valueOf(months));
    }

}
