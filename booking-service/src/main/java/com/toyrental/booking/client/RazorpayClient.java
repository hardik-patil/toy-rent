package com.toyrental.booking.client;

import com.toyrental.booking.dto.RazorpayOrderRequest;
import com.toyrental.booking.dto.RazorpayOrderResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Talks to the WireMock Razorpay stub (CLAUDE.md's "WireMock Stubs" section, Stub 1). Wired
 * without a circuit breaker deliberately — CLAUDE.md's Performance Engineering section lists "no
 * circuit breaker on the Razorpay call path" as an intentional bottleneck to find via JMeter in
 * Sprint 7, not to pre-fix now, even though resilience4j's "razorpay" instance is already
 * configured in application.yml.
 */
@FeignClient(name = "razorpay", url = "${wiremock.base-url}")
public interface RazorpayClient {

    @PostMapping("/v1/orders")
    RazorpayOrderResponse createOrder(@RequestBody RazorpayOrderRequest request);

}
