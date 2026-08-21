package com.toyrental.toy.kafka;

import com.toyrental.toy.entity.ProcessedEvent;
import com.toyrental.toy.repository.ProcessedEventRepository;
import com.toyrental.toy.service.AvailabilityService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Keeps toy-service's Couchbase availability cache and toy_availability_log in sync with booking
 * outcomes decided by booking-service. Per CLAUDE.md's "Booking Flow": booking.confirmed blocks
 * the toy's dates, booking.cancelled releases them.
 */
@Slf4j
@Component
public class BookingEventConsumer {

    private static final String CORRELATION_ID_MDC_KEY = "correlationId";

    private final ProcessedEventRepository processedEventRepository;
    private final AvailabilityService availabilityService;

    public BookingEventConsumer(ProcessedEventRepository processedEventRepository,
                                 AvailabilityService availabilityService) {
        this.processedEventRepository = processedEventRepository;
        this.availabilityService = availabilityService;
    }

    @KafkaListener(topics = "booking.confirmed", groupId = "toy-service-cg")
    public void onBookingConfirmed(BookingEventEnvelope event) {
        process(event, payload -> availabilityService.blockDates(
                payload.toyId(), payload.bookingId(), payload.startDate(), payload.endDate()));
    }

    @KafkaListener(topics = "booking.cancelled", groupId = "toy-service-cg")
    public void onBookingCancelled(BookingEventEnvelope event) {
        process(event, payload -> availabilityService.releaseDates(payload.toyId(), payload.bookingId()));
    }

    /**
     * No @Transactional here deliberately: AvailabilityService's blockDates/releaseDates already
     * carry their own transactional boundary for the Postgres writes they make (a method call
     * from a Spring-managed bean, so the proxy applies normally), and the Couchbase upsert inside
     * them isn't part of any JDBC transaction regardless. Marking process() itself @Transactional
     * would be a no-op bug: KafkaListener methods invoke it via `this.process(...)`, a plain
     * in-JVM call that bypasses this bean's transactional proxy entirely (Spring AOP
     * self-invocation limitation). If saving the processed-event row fails after a successful
     * block/release, the event is simply retried by Kafka; block/release are idempotent per
     * bookingId, so replay is safe.
     */
    private void process(BookingEventEnvelope event, java.util.function.Consumer<BookingEventPayload> action) {
        MDC.put(CORRELATION_ID_MDC_KEY, event.correlationId());
        try {
            log.info("Received eventId={} eventType={} bookingId={}",
                    event.eventId(), event.eventType(), event.payload().bookingId());

            if (processedEventRepository.existsById(event.eventId())) {
                log.info("Skipping already-processed eventId={}", event.eventId());
                return;
            }

            action.accept(event.payload());

            processedEventRepository.save(ProcessedEvent.builder()
                    .eventId(event.eventId())
                    .eventType(event.eventType())
                    .build());

            log.info("Processed eventId={} eventType={}", event.eventId(), event.eventType());
        } finally {
            MDC.remove(CORRELATION_ID_MDC_KEY);
        }
    }

}
