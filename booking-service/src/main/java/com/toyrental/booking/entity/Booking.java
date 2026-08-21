package com.toyrental.booking.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "bookings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Booking {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "toy_id", nullable = false, length = 36)
    private String toyId;

    @Column(name = "customer_id", nullable = false, length = 36)
    private String customerId;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "rental_type", nullable = false)
    private RentalType rentalType;

    @Column(name = "rental_amount", nullable = false)
    private BigDecimal rentalAmount;

    @Column(name = "deposit_amount", nullable = false)
    private BigDecimal depositAmount;

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private BookingStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false)
    private PaymentStatus paymentStatus;

    @Column(name = "delivery_flat")
    private String deliveryFlat;

    @Column(name = "delivery_building")
    private String deliveryBuilding;

    @Column(name = "delivery_area")
    private String deliveryArea;

    @Column(name = "delivery_city")
    private String deliveryCity;

    @Column(name = "delivery_pincode")
    private String deliveryPincode;

    @Column(name = "cancelled_by")
    private String cancelledBy;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "returned_at")
    private LocalDateTime returnedAt;

    @Column(name = "return_condition")
    private String returnCondition;

    @Column(name = "damage_notes")
    private String damageNotes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

}
