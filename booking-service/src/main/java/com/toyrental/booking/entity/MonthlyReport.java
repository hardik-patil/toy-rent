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

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "monthly_reports")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MonthlyReport {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "month", nullable = false)
    private int month;

    @Column(name = "year", nullable = false)
    private int year;

    @Column(name = "total_bookings", nullable = false)
    private int totalBookings;

    @Column(name = "total_revenue", nullable = false)
    private BigDecimal totalRevenue;

    @Column(name = "total_deposits", nullable = false)
    private BigDecimal totalDeposits;

    @Column(name = "pending_returns", nullable = false)
    private int pendingReturns;

    @Column(name = "top_toy_id")
    private String topToyId;

    @Column(name = "top_toy_name")
    private String topToyName;

    @Column(name = "pdf_storage_path")
    private String pdfStoragePath;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ReportStatus status;

    @Column(name = "generated_at")
    private LocalDateTime generatedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

}
