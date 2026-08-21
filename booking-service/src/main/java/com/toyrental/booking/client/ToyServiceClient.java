package com.toyrental.booking.client;

import com.toyrental.booking.dto.ToyAvailabilityResponse;
import com.toyrental.booking.dto.ToyDetailResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "toy-service", url = "${feign.toy-service.url}")
public interface ToyServiceClient {

    @GetMapping("/internal/v1/toys/{toyId}")
    ToyDetailResponse getToy(@PathVariable("toyId") String toyId);

    @GetMapping("/api/v1/toys/{toyId}/availability")
    ToyAvailabilityResponse checkAvailability(@PathVariable("toyId") String toyId,
                                               @RequestParam("from") String from,
                                               @RequestParam("to") String to);

}
