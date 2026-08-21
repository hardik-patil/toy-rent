package com.toyrental.booking.dto;

import java.math.BigDecimal;

public record RazorpayOrderRequest(BigDecimal amount, String currency, String receipt) {
}
