package com.toyrental.booking.controller;

import com.toyrental.booking.dto.BookingCancelRequest;
import com.toyrental.booking.dto.BookingExtendRequest;
import com.toyrental.booking.dto.BookingRequest;
import com.toyrental.booking.dto.BookingResponse;
import com.toyrental.booking.service.BookingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Bookings", description = "Create, view, cancel and extend toy rental bookings")
@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @Operation(summary = "Create a booking")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody BookingRequest request) {
        return bookingService.create(jwt.getSubject(), request);
    }

    @Operation(summary = "Booking detail")
    @GetMapping("/{bookingId}")
    public BookingResponse getById(@AuthenticationPrincipal Jwt jwt, @PathVariable String bookingId) {
        return bookingService.getByIdForCustomer(bookingId, jwt.getSubject());
    }

    @Operation(summary = "Download the PDF receipt for a booking")
    @GetMapping(value = "/{bookingId}/receipt", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> receipt(@AuthenticationPrincipal Jwt jwt, @PathVariable String bookingId) {
        byte[] pdf = bookingService.generateReceipt(bookingId, jwt.getSubject());
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + bookingId + "-receipt.pdf\"")
                .body(pdf);
    }

    @Operation(summary = "Cancel a booking")
    @PutMapping("/{bookingId}/cancel")
    public BookingResponse cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable String bookingId,
                                   @RequestBody(required = false) BookingCancelRequest request) {
        return bookingService.cancel(bookingId, jwt.getSubject(),
                request != null ? request : new BookingCancelRequest(null));
    }

    @Operation(summary = "Extend a booking's end date")
    @PutMapping("/{bookingId}/extend")
    public BookingResponse extend(@AuthenticationPrincipal Jwt jwt, @PathVariable String bookingId,
                                   @Valid @RequestBody BookingExtendRequest request) {
        return bookingService.extend(bookingId, jwt.getSubject(), request);
    }

}
