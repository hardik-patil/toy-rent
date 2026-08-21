package com.toyrental.booking.controller;

import com.toyrental.booking.dto.AddressUpdateRequest;
import com.toyrental.booking.dto.CustomerProfileUpdateRequest;
import com.toyrental.booking.dto.CustomerRequest;
import com.toyrental.booking.dto.CustomerResponse;
import com.toyrental.booking.dto.LoginRequest;
import com.toyrental.booking.dto.LoginResponse;
import com.toyrental.booking.dto.PagedResponse;
import com.toyrental.booking.dto.BookingResponse;
import com.toyrental.booking.service.BookingService;
import com.toyrental.booking.service.CustomerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Customers", description = "Customer registration, login, profile and bookings")
@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {

    private final CustomerService customerService;
    private final BookingService bookingService;

    public CustomerController(CustomerService customerService, BookingService bookingService) {
        this.customerService = customerService;
        this.bookingService = bookingService;
    }

    @Operation(summary = "Register a new customer")
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerResponse register(@Valid @RequestBody CustomerRequest request) {
        return customerService.register(request);
    }

    @Operation(summary = "Login and receive a JWT")
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return customerService.login(request);
    }

    @Operation(summary = "My profile")
    @GetMapping("/me")
    public CustomerResponse me(@AuthenticationPrincipal Jwt jwt) {
        return customerService.getById(jwt.getSubject());
    }

    @Operation(summary = "Update my profile")
    @PutMapping("/me")
    public CustomerResponse updateProfile(@AuthenticationPrincipal Jwt jwt,
                                           @Valid @RequestBody CustomerProfileUpdateRequest request) {
        return customerService.updateProfile(jwt.getSubject(), request);
    }

    @Operation(summary = "Update my delivery address")
    @PutMapping("/me/address")
    public CustomerResponse updateAddress(@AuthenticationPrincipal Jwt jwt,
                                           @Valid @RequestBody AddressUpdateRequest request) {
        return customerService.updateAddress(jwt.getSubject(), request);
    }

    @Operation(summary = "My bookings")
    @GetMapping("/me/bookings")
    public PagedResponse<BookingResponse> myBookings(@AuthenticationPrincipal Jwt jwt, Pageable pageable) {
        return bookingService.getMyBookings(jwt.getSubject(), pageable);
    }

}
