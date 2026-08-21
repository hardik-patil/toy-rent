package com.toyrental.toy.entity;

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

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "toy_availability_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ToyAvailabilityLog {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "toy_id", nullable = false, length = 36)
    private String toyId;

    @Column(name = "booking_id", length = 36)
    private String bookingId;

    @Column(name = "blocked_from", nullable = false)
    private LocalDate blockedFrom;

    @Column(name = "blocked_to", nullable = false)
    private LocalDate blockedTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false)
    private AvailabilityAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason")
    private AvailabilityReason reason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

}
