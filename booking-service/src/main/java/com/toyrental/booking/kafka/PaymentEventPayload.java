package com.toyrental.booking.kafka;

import java.math.BigDecimal;

public record PaymentEventPayload(
        String bookingId,
        String customerId,
        BigDecimal amount,
        String failureReason
) {
}
