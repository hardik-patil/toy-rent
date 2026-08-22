package com.toyrental.booking.service;

import com.toyrental.booking.client.ToyServiceClient;
import com.toyrental.booking.dto.ReportAggregate;
import com.toyrental.booking.dto.RevenueByWeekResult;
import com.toyrental.booking.dto.TopToyResult;
import com.toyrental.booking.entity.Booking;
import com.toyrental.booking.entity.BookingStatus;
import com.toyrental.booking.repository.BookingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Aggregates a month's bookings for the month-end report. "For the month" means bookings whose
 * start_date falls in that month and that actually materialized — CONFIRMED/ACTIVE/RETURNED/
 * OVERDUE, excluding PENDING (never paid) and CANCELLED (didn't happen).
 */
@Slf4j
@Service
public class ReportService {

    private static final List<BookingStatus> MATERIALIZED_STATUSES =
            List.of(BookingStatus.CONFIRMED, BookingStatus.ACTIVE, BookingStatus.RETURNED, BookingStatus.OVERDUE);
    private static final List<BookingStatus> PENDING_RETURN_STATUSES =
            List.of(BookingStatus.ACTIVE, BookingStatus.OVERDUE);

    private final BookingRepository bookingRepository;
    private final ToyServiceClient toyServiceClient;

    public ReportService(BookingRepository bookingRepository, ToyServiceClient toyServiceClient) {
        this.bookingRepository = bookingRepository;
        this.toyServiceClient = toyServiceClient;
    }

    @Transactional(readOnly = true)
    public ReportAggregate aggregate(int month, int year) {
        LocalDate monthStart = LocalDate.of(year, month, 1);
        LocalDate monthEnd = monthStart.withDayOfMonth(monthStart.lengthOfMonth());
        List<Booking> bookings = bookingRepository.findByStartDateBetweenAndStatusIn(
                monthStart, monthEnd, MATERIALIZED_STATUSES);

        int totalBookings = bookings.size();
        BigDecimal totalRevenue = sum(bookings, Booking::getRentalAmount);
        BigDecimal totalDeposits = sum(bookings, Booking::getDepositAmount);
        int pendingReturns = (int) bookings.stream()
                .filter(b -> PENDING_RETURN_STATUSES.contains(b.getStatus()))
                .count();

        TopToyResult topToy = findTopToy(bookings);
        List<RevenueByWeekResult> revenueByWeek = revenueByWeek(bookings);

        return new ReportAggregate(totalBookings, totalRevenue, totalDeposits, pendingReturns, topToy, revenueByWeek);
    }

    private BigDecimal sum(List<Booking> bookings, java.util.function.Function<Booking, BigDecimal> field) {
        return bookings.stream().map(field).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private TopToyResult findTopToy(List<Booking> bookings) {
        Map<String, List<Booking>> byToy = bookings.stream().collect(Collectors.groupingBy(Booking::getToyId));
        return byToy.entrySet().stream()
                .max(Comparator.comparingInt(e -> e.getValue().size()))
                .map(e -> new TopToyResult(e.getKey(), resolveToyName(e.getKey()), e.getValue().size()))
                .orElse(null);
    }

    private String resolveToyName(String toyId) {
        try {
            return toyServiceClient.getToy(toyId).name();
        } catch (RuntimeException e) {
            log.warn("Could not resolve name for top toy id={}, falling back to the id", toyId, e);
            return toyId;
        }
    }

    private List<RevenueByWeekResult> revenueByWeek(List<Booking> bookings) {
        Map<Integer, BigDecimal> byWeek = new TreeMap<>();
        for (Booking booking : bookings) {
            int week = ((booking.getStartDate().getDayOfMonth() - 1) / 7) + 1;
            byWeek.merge(week, booking.getRentalAmount(), BigDecimal::add);
        }
        return byWeek.entrySet().stream()
                .map(e -> new RevenueByWeekResult(e.getKey(), e.getValue()))
                .toList();
    }

}
