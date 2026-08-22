package com.toyrental.booking.kafka;

import java.time.Instant;

/** Published once a month-end report finishes generating (or fails). No consumers yet — CLAUDE.md's topic table lists this one as "(future)". */
public record MonthlyReportGeneratedEnvelope(
        String eventId,
        String eventType,
        String eventVersion,
        Instant occurredAt,
        String correlationId,
        String source,
        MonthlyReportGeneratedPayload payload
) {
}
