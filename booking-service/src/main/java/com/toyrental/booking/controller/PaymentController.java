package com.toyrental.booking.controller;

import com.toyrental.booking.dto.PaymentRequest;
import com.toyrental.booking.dto.PaymentResponse;
import com.toyrental.booking.dto.RazorpayWebhookRequest;
import com.toyrental.booking.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Payments", description = "Payment status and the Razorpay webhook callback")
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @Operation(summary = "Re-fetch (or retry) the pending payment for a booking")
    @PostMapping("/initiate")
    public PaymentResponse initiate(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PaymentRequest request) {
        return paymentService.initiate(request.bookingId(), jwt.getSubject());
    }

    @Operation(summary = "Razorpay webhook callback (called by the payment gateway, not the client app)")
    @PostMapping("/webhook")
    public void webhook(@Valid @RequestBody RazorpayWebhookRequest request) {
        paymentService.handleWebhook(request);
    }

    @Operation(summary = "Payment status")
    @GetMapping("/{paymentId}")
    public PaymentResponse getById(@PathVariable String paymentId) {
        return paymentService.getById(paymentId);
    }

    @Operation(summary = "Refund a booking's deposit (admin)")
    @PostMapping("/{bookingId}/refund")
    public PaymentResponse refund(@PathVariable String bookingId) {
        return paymentService.refundDeposit(bookingId);
    }

}
