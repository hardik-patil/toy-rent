package com.toyrental.toy.kafka;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * The event envelope every Kafka message follows per CLAUDE.md's "Kafka Event Envelope" spec.
 * Shared shape for both booking.confirmed and booking.cancelled — same payload fields apply to
 * either.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
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
