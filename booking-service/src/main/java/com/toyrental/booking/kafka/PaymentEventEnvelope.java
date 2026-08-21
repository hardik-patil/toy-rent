package com.toyrental.booking.kafka;

import java.time.Instant;

/**
 * The event envelope every Kafka message follows per CLAUDE.md's "Kafka Event Envelope" spec.
 * Published and consumed by booking-service itself (payment.success/payment.failed, consumer
 * group booking-internal-cg per CLAUDE.md's topic table) — supplementary/audit signal alongside
 * the synchronous payment-confirmation already done in PaymentService.handleWebhook(), not a
 * replacement for it (that flow is CLAUDE.md's literal "Booking Flow — Critical Logic", which
 * doesn't route through this topic).
 */
public record PaymentEventEnvelope(
        String eventId,
        String eventType,
        String eventVersion,
        Instant occurredAt,
        String correlationId,
        String source,
        PaymentEventPayload payload
) {
}
