package com.toyrental.booking.kafka;

import com.toyrental.booking.config.CorrelationIdFilter;
import com.toyrental.booking.entity.Booking;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

import static com.toyrental.booking.util.IdGenerator.shortId;

/**
 * Publishes booking.confirmed/booking.cancelled per CLAUDE.md's Kafka topic table (key=toyId,
 * consumed by toy-service to block/release Couchbase availability). This is emitted synchronously
 * as part of PaymentService's webhook handling and BookingService's cancel flow — CLAUDE.md's
 * "Booking Flow" documents the publish as step 4 of the payment webhook itself, so it belongs to
 * this sprint's PaymentService/BookingService work rather than waiting on the rest of Sprint 4's
 * consumer-side wiring (DLTs, PaymentEventConsumer, notification consumer, overdue job).
 */
@Slf4j
@Component
public class BookingEventProducer {

    private static final String CONFIRMED_TOPIC = "booking.confirmed";
    private static final String CANCELLED_TOPIC = "booking.cancelled";
    private static final String OVERDUE_TOPIC = "booking.overdue";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public BookingEventProducer(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishBookingConfirmed(Booking booking) {
        publish(CONFIRMED_TOPIC, "BOOKING_CONFIRMED", booking, booking.getToyId());
    }

    public void publishBookingCancelled(Booking booking) {
        publish(CANCELLED_TOPIC, "BOOKING_CANCELLED", booking, booking.getToyId());
    }

    /** Keyed by customerId, not toyId, per CLAUDE.md's Kafka topic table. */
    public void publishBookingOverdue(Booking booking) {
        publish(OVERDUE_TOPIC, "BOOKING_OVERDUE", booking, booking.getCustomerId());
    }

    private void publish(String topic, String eventType, Booking booking, String key) {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        BookingEventPayload payload = new BookingEventPayload(
                booking.getId(), booking.getToyId(), booking.getCustomerId(),
                booking.getStartDate(), booking.getEndDate());
        BookingEventEnvelope envelope = new BookingEventEnvelope(
                shortId("evt"), eventType, "v1", Instant.now(),
                correlationId, "booking-service", payload);

        kafkaTemplate.send(topic, key, envelope);
        log.info("Published eventId={} eventType={} topic={} bookingId={}",
                envelope.eventId(), eventType, topic, booking.getId());
    }

}
