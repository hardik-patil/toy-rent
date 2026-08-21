package com.toyrental.booking.kafka;

import java.time.Instant;

/** The event envelope every Kafka message follows per CLAUDE.md's "Kafka Event Envelope" spec. */
public record BookingEventEnvelope(
        String eventId,
        String eventType,
        String eventVersion,
        Instant occurredAt,
        String correlationId,
        String source,
        BookingEventPayload payload
) {
}
