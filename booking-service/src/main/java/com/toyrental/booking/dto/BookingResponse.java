package com.toyrental.booking.dto;

import com.toyrental.booking.entity.Booking;
import com.toyrental.booking.entity.BookingStatus;
import com.toyrental.booking.entity.PaymentStatus;
import com.toyrental.booking.entity.RentalType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record BookingResponse(
        String id,
        String toyId,
        String customerId,
        LocalDate startDate,
        LocalDate endDate,
        RentalType rentalType,
        BigDecimal rentalAmount,
        BigDecimal depositAmount,
        BigDecimal totalAmount,
        BookingStatus status,
        PaymentStatus paymentStatus,
        String deliveryFlat,
        String deliveryBuilding,
        String deliveryArea,
        String deliveryCity,
        String deliveryPincode,
        String razorpayOrderId,
        LocalDateTime createdAt
) {

    public static BookingResponse from(Booking booking, String razorpayOrderId) {
        return new BookingResponse(
                booking.getId(),
                booking.getToyId(),
                booking.getCustomerId(),
                booking.getStartDate(),
                booking.getEndDate(),
                booking.getRentalType(),
                booking.getRentalAmount(),
                booking.getDepositAmount(),
                booking.getTotalAmount(),
                booking.getStatus(),
                booking.getPaymentStatus(),
                booking.getDeliveryFlat(),
                booking.getDeliveryBuilding(),
                booking.getDeliveryArea(),
                booking.getDeliveryCity(),
                booking.getDeliveryPincode(),
                razorpayOrderId,
                booking.getCreatedAt()
        );
    }
}
