package com.toyrental.toy.controller;

import com.toyrental.toy.dto.AvailabilityResponse;
import com.toyrental.toy.dto.InternalAvailabilityUpdateRequest;
import com.toyrental.toy.dto.ToyResponse;
import com.toyrental.toy.entity.AvailabilityAction;
import com.toyrental.toy.service.AvailabilityService;
import com.toyrental.toy.service.ToyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service surface, not for browser/customer use — called by booking-service (via
 * Feign) and reachable for manual ops corrections. Not JWT-protected: SecurityConfig permits
 * /internal/v1/toys/** on the assumption this is only network-reachable from inside the cluster,
 * per k8s/network-policy.yaml (Sprint 8).
 */
@Tag(name = "Toys Internal", description = "Service-to-service toy lookups and availability overrides")
@RestController
@RequestMapping("/internal/v1/toys")
public class InternalToyController {

    private final ToyService toyService;
    private final AvailabilityService availabilityService;

    public InternalToyController(ToyService toyService, AvailabilityService availabilityService) {
        this.toyService = toyService;
        this.availabilityService = availabilityService;
    }

    @Operation(summary = "Toy detail, for booking-service's pre-booking existence check")
    @GetMapping("/{toyId}")
    public ToyResponse getById(@PathVariable String toyId) {
        return toyService.getById(toyId);
    }

    @Operation(summary = "Block or release a toy's availability for a booking (manual override; "
            + "normally applied by the booking.confirmed/booking.cancelled Kafka consumers)")
    @PutMapping("/{toyId}/availability")
    public AvailabilityResponse updateAvailability(@PathVariable String toyId,
                                                     @Valid @RequestBody InternalAvailabilityUpdateRequest request) {
        if (request.action() == AvailabilityAction.BLOCKED) {
            availabilityService.blockDates(toyId, request.bookingId(), request.from(), request.to());
        } else {
            availabilityService.releaseDates(toyId, request.bookingId());
        }
        return availabilityService.getCalendar(toyId);
    }

}
