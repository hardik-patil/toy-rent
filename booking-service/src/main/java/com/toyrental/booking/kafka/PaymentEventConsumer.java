package com.toyrental.booking.kafka;

import com.toyrental.booking.entity.ProcessedEvent;
import com.toyrental.booking.repository.ProcessedEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes booking-service's own payment.success/payment.failed events (consumer group
 * booking-internal-cg per CLAUDE.md's topic table). Deliberately minimal/audit-only — the actual
 * payment confirmation (booking status, payment rows, booking.confirmed) already happens
 * synchronously in PaymentService.handleWebhook() per CLAUDE.md's literal "Booking Flow" text;
 * this just demonstrates and exercises the documented topic/consumer-group pairing.
 */
@Slf4j
@Component
public class PaymentEventConsumer {

    private final ProcessedEventRepository processedEventRepository;

    public PaymentEventConsumer(ProcessedEventRepository processedEventRepository) {
        this.processedEventRepository = processedEventRepository;
    }

    @KafkaListener(topics = "payment.success", groupId = "booking-internal-cg")
    public void onPaymentSuccess(PaymentEventEnvelope event) {
        process(event);
    }

    @KafkaListener(topics = "payment.failed", groupId = "booking-internal-cg")
    public void onPaymentFailed(PaymentEventEnvelope event) {
        process(event);
    }

    // No @Transactional here: called via `this.process(...)` from the @KafkaListener methods, a
    // plain in-JVM call that bypasses this bean's transactional proxy (Spring AOP self-invocation
    // limitation) — see toy-service's BookingEventConsumer for the full explanation of the same
    // pattern. processedEventRepository.save() still runs as its own auto-commit-ish unit via
    // Spring Data's default transactional repository methods.
    private void process(PaymentEventEnvelope event) {
        MDC.put("correlationId", event.correlationId());
        try {
            log.info("Received eventId={} eventType={} bookingId={}",
                    event.eventId(), event.eventType(), event.payload().bookingId());

            if (processedEventRepository.existsById(event.eventId())) {
                log.info("Skipping already-processed eventId={}", event.eventId());
                return;
            }

            processedEventRepository.save(ProcessedEvent.builder()
                    .eventId(event.eventId())
                    .eventType(event.eventType())
                    .build());
            log.info("Processed eventId={} eventType={} bookingId={} amount={}",
                    event.eventId(), event.eventType(), event.payload().bookingId(), event.payload().amount());
        } finally {
            MDC.remove("correlationId");
        }
    }

}
