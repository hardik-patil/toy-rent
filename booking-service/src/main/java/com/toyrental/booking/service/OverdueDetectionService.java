package com.toyrental.booking.service;

import com.toyrental.booking.entity.Booking;
import com.toyrental.booking.entity.BookingStatus;
import com.toyrental.booking.kafka.BookingEventProducer;
import com.toyrental.booking.repository.BookingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Finds ACTIVE bookings past their end_date, flips them to OVERDUE, and publishes booking.overdue
 * for each (consumed by BookingNotificationConsumer to send the reminder). Uses the wall clock
 * rather than a LogicalDateService for the same reason documented on BookingService's
 * todaysDeliveries/todaysPickups/overdue methods — booking-service has no LogicalDateService or
 * logical-date Couchbase bucket of its own; that bucket is toy-service-owned per CLAUDE.md's
 * database ownership rules.
 */
@Slf4j
@Service
public class OverdueDetectionService {

    private final BookingRepository bookingRepository;
    private final BookingEventProducer bookingEventProducer;

    public OverdueDetectionService(BookingRepository bookingRepository, BookingEventProducer bookingEventProducer) {
        this.bookingRepository = bookingRepository;
        this.bookingEventProducer = bookingEventProducer;
    }

    @Scheduled(initialDelayString = "${overdue-check.initial-delay-ms:30000}",
            fixedRateString = "${overdue-check.interval-ms:21600000}")
    @Transactional
    public void detectAndPublishOverdueBookings() {
        Page<Booking> overdue = bookingRepository.findByStatusAndEndDateBefore(
                BookingStatus.ACTIVE, LocalDate.now(), Pageable.unpaged());

        if (overdue.isEmpty()) {
            log.debug("Overdue check: no ACTIVE bookings past their end date");
            return;
        }

        for (Booking booking : overdue) {
            booking.setStatus(BookingStatus.OVERDUE);
            bookingRepository.save(booking);
            bookingEventProducer.publishBookingOverdue(booking);
            log.info("Marked booking id={} OVERDUE (endDate={})", booking.getId(), booking.getEndDate());
        }
    }

}
