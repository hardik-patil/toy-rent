package com.toyrental.booking.dto;

public record RazorpayOrderResponse(String id, String status, long amount, String currency, String receipt) {
}
