package com.toyrental.booking.dto;

import com.toyrental.booking.entity.Payment;
import com.toyrental.booking.entity.PaymentMethod;
import com.toyrental.booking.entity.PaymentStatus;
import com.toyrental.booking.entity.PaymentType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PaymentResponse(
        String id,
        String bookingId,
        BigDecimal amount,
        PaymentType type,
        PaymentMethod method,
        PaymentStatus status,
        String razorpayOrderId,
        String razorpayPaymentId,
        LocalDateTime createdAt
) {

    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getBookingId(),
                payment.getAmount(),
                payment.getType(),
                payment.getMethod(),
                payment.getStatus(),
                payment.getRazorpayOrderId(),
                payment.getRazorpayPaymentId(),
                payment.getCreatedAt()
        );
    }
}
