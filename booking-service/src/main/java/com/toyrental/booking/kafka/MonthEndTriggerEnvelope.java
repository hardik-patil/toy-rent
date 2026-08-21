package com.toyrental.booking.kafka;

import java.time.Instant;

/** Published by AdminReportController's trigger endpoint, consumed by MonthEndTriggerConsumer. */
public record MonthEndTriggerEnvelope(
        String eventId,
        String eventType,
        String eventVersion,
        Instant occurredAt,
        String correlationId,
        String source,
        MonthEndTriggerPayload payload
) {
}
