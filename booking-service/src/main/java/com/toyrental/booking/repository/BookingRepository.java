package com.toyrental.booking.repository;

import com.toyrental.booking.entity.Booking;
import com.toyrental.booking.entity.BookingStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, String> {

    Optional<Booking> findByIdAndCustomerId(String id, String customerId);

    Page<Booking> findByCustomerId(String customerId, Pageable pageable);

    /**
     * CLAUDE.md's Booking Flow step 4b: "SELECT ... FOR UPDATE — CRITICAL: pessimistic lock",
     * checked inside the same transaction as the booking insert to close the race between two
     * concurrent requests for the same toy/date-range against this service's own database (the
     * authoritative Couchbase availability check happens first, but only this lock actually
     * prevents a double-insert under concurrency).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Booking b WHERE b.toyId = :toyId AND b.status IN :activeStatuses "
            + "AND b.startDate <= :endDate AND b.endDate >= :startDate "
            + "AND (:excludeBookingId IS NULL OR b.id <> :excludeBookingId)")
    List<Booking> findOverlapping(@Param("toyId") String toyId,
                                   @Param("startDate") LocalDate startDate,
                                   @Param("endDate") LocalDate endDate,
                                   @Param("activeStatuses") List<BookingStatus> activeStatuses,
                                   @Param("excludeBookingId") String excludeBookingId);

    @Query("SELECT b FROM Booking b WHERE (:status IS NULL OR b.status = :status)")
    Page<Booking> browse(@Param("status") BookingStatus status, Pageable pageable);

    Page<Booking> findByStatusAndStartDate(BookingStatus status, LocalDate startDate, Pageable pageable);

    Page<Booking> findByStatusAndEndDate(BookingStatus status, LocalDate endDate, Pageable pageable);

    Page<Booking> findByStatusAndEndDateBefore(BookingStatus status, LocalDate endDate, Pageable pageable);

}
