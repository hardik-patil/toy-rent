package com.toyrental.booking.controller;

import com.toyrental.booking.dto.BookingResponse;
import com.toyrental.booking.dto.BookingReturnRequest;
import com.toyrental.booking.dto.PagedResponse;
import com.toyrental.booking.entity.BookingStatus;
import com.toyrental.booking.service.BookingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Bookings Admin", description = "Admin-only booking operations (requires ROLE_ADMIN)")
@RestController
@RequestMapping("/api/v1/admin/bookings")
public class AdminBookingController {

    private final BookingService bookingService;

    public AdminBookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @Operation(summary = "All bookings, optionally filtered by status")
    @GetMapping
    public PagedResponse<BookingResponse> browse(@RequestParam(required = false) BookingStatus status, Pageable pageable) {
        return bookingService.adminBrowse(status, pageable);
    }

    @Operation(summary = "Today's deliveries")
    @GetMapping("/today/deliveries")
    public PagedResponse<BookingResponse> todaysDeliveries(Pageable pageable) {
        return bookingService.todaysDeliveries(pageable);
    }

    @Operation(summary = "Today's pickups")
    @GetMapping("/today/pickups")
    public PagedResponse<BookingResponse> todaysPickups(Pageable pageable) {
        return bookingService.todaysPickups(pageable);
    }

    @Operation(summary = "Overdue returns")
    @GetMapping("/overdue")
    public PagedResponse<BookingResponse> overdue(Pageable pageable) {
        return bookingService.overdue(pageable);
    }

    @Operation(summary = "Mark a booking as returned")
    @PutMapping("/{bookingId}/return")
    public BookingResponse markReturned(@PathVariable String bookingId, @Valid @RequestBody BookingReturnRequest request) {
        return bookingService.markReturned(bookingId, request);
    }

    @Operation(summary = "Manually confirm a pending booking (e.g. cash/COD)")
    @PutMapping("/{bookingId}/confirm")
    public BookingResponse confirm(@PathVariable String bookingId) {
        return bookingService.confirmManually(bookingId);
    }

}
