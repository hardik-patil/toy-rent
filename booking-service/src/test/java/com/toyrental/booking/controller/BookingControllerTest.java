package com.toyrental.booking.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.toyrental.booking.dto.BookingRequest;
import com.toyrental.booking.dto.BookingResponse;
import com.toyrental.booking.entity.BookingStatus;
import com.toyrental.booking.entity.PaymentStatus;
import com.toyrental.booking.entity.RentalType;
import com.toyrental.booking.exception.BookingNotFoundException;
import com.toyrental.booking.exception.ToyNotAvailableException;
import com.toyrental.booking.service.BookingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = BookingController.class)
class BookingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private BookingService bookingService;

    private static final String CUSTOMER_ID = "cust-0001";

    private BookingResponse sampleBooking() {
        return new BookingResponse("bkg-001", "toy-001", CUSTOMER_ID, LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 7), RentalType.WEEKLY, BigDecimal.valueOf(299), BigDecimal.valueOf(1500),
                BigDecimal.valueOf(1799), BookingStatus.PENDING, PaymentStatus.PENDING, "B-204",
                "Neelkanth Heights", "Kharghar", "Navi Mumbai", "410210", "order_mock123", LocalDateTime.now());
    }

    private BookingRequest sampleRequest() {
        return new BookingRequest("toy-001", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 7),
                RentalType.WEEKLY, "B-204", "Neelkanth Heights", "Kharghar", "Navi Mumbai", "410210");
    }

    @Test
    void createReturnsCreatedBooking() throws Exception {
        when(bookingService.create(eq(CUSTOMER_ID), any())).thenReturn(sampleBooking());

        mockMvc.perform(post("/api/v1/bookings")
                        .with(jwt().jwt(builder -> builder.subject(CUSTOMER_ID)))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("bkg-001"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.razorpayOrderId").value("order_mock123"));
    }

    @Test
    void createReturns409WhenToyNotAvailable() throws Exception {
        when(bookingService.create(eq(CUSTOMER_ID), any()))
                .thenThrow(new ToyNotAvailableException("Toy toy-001 is not available"));

        mockMvc.perform(post("/api/v1/bookings")
                        .with(jwt().jwt(builder -> builder.subject(CUSTOMER_ID)))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("TOY_NOT_AVAILABLE"));
    }

    @Test
    void createReturns400WhenValidationFails() throws Exception {
        String invalidJson = "{\"toyId\":\"\"}";

        mockMvc.perform(post("/api/v1/bookings")
                        .with(jwt().jwt(builder -> builder.subject(CUSTOMER_ID)))
                        .contentType("application/json")
                        .content(invalidJson))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getByIdReturns404WhenNotFound() throws Exception {
        when(bookingService.getByIdForCustomer(anyString(), eq(CUSTOMER_ID)))
                .thenThrow(new BookingNotFoundException("bkg-999"));

        mockMvc.perform(get("/api/v1/bookings/bkg-999")
                        .with(jwt().jwt(builder -> builder.subject(CUSTOMER_ID))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("BOOKING_NOT_FOUND"));
    }

}
