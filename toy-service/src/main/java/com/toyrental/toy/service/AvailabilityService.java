package com.toyrental.toy.service;

import com.toyrental.toy.couchbase.CouchbaseAvailabilityRepository;
import com.toyrental.toy.couchbase.ToyAvailabilityDocument;
import com.toyrental.toy.dto.AvailabilityResponse;
import com.toyrental.toy.dto.AvailabilityResponse.BlockedDateRange;
import com.toyrental.toy.entity.Toy;
import com.toyrental.toy.entity.ToyStatus;
import com.toyrental.toy.exception.ToyNotFoundException;
import com.toyrental.toy.repository.ToyRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class AvailabilityService {

    private final ToyRepository toyRepository;
    private final CouchbaseAvailabilityRepository availabilityRepository;
    private final Counter cacheHitCounter;
    private final Counter cacheMissCounter;

    public AvailabilityService(ToyRepository toyRepository,
                                CouchbaseAvailabilityRepository availabilityRepository,
                                MeterRegistry meterRegistry) {
        this.toyRepository = toyRepository;
        this.availabilityRepository = availabilityRepository;
        this.cacheHitCounter = Counter.builder("toy.availability.cache.hit")
                .description("Couchbase availability cache hits")
                .register(meterRegistry);
        this.cacheMissCounter = Counter.builder("toy.availability.cache.miss")
                .description("Couchbase availability cache misses")
                .register(meterRegistry);
    }

    @Transactional(readOnly = true)
    public AvailabilityResponse checkAvailability(String toyId, LocalDate from, LocalDate to) {
        Toy toy = requireToy(toyId);
        ToyAvailabilityDocument document = loadOrDefault(toy);

        boolean available = document.getBlockedDates().stream()
                .noneMatch(blocked -> overlaps(blocked.getFrom(), blocked.getTo(), from, to));

        return toResponse(document, available);
    }

    @Transactional(readOnly = true)
    public AvailabilityResponse getCalendar(String toyId) {
        Toy toy = requireToy(toyId);
        ToyAvailabilityDocument document = loadOrDefault(toy);
        return toResponse(document, null);
    }

    private Toy requireToy(String toyId) {
        return toyRepository.findByIdAndActiveTrue(toyId)
                .orElseThrow(() -> new ToyNotFoundException(toyId));
    }

    private ToyAvailabilityDocument loadOrDefault(Toy toy) {
        Optional<ToyAvailabilityDocument> existing = availabilityRepository.findByToyId(toy.getId());
        if (existing.isPresent()) {
            cacheHitCounter.increment();
            return existing.get();
        }

        cacheMissCounter.increment();
        log.debug("No Couchbase availability document for toyId={}, treating as fully available", toy.getId());
        return ToyAvailabilityDocument.builder()
                .id(ToyAvailabilityDocument.documentId(toy.getId()))
                .toyId(toy.getId())
                .toyName(toy.getName())
                .status(toy.getStatus() == ToyStatus.AVAILABLE ? "AVAILABLE" : toy.getStatus().name())
                .blockedDates(List.of())
                .nextAvailable(LocalDate.now())
                .lastUpdated(Instant.now())
                .build();
    }

    private boolean overlaps(LocalDate blockedFrom, LocalDate blockedTo, LocalDate from, LocalDate to) {
        return !blockedTo.isBefore(from) && !blockedFrom.isAfter(to);
    }

    private AvailabilityResponse toResponse(ToyAvailabilityDocument document, Boolean available) {
        List<BlockedDateRange> blockedDates = document.getBlockedDates().stream()
                .map(b -> new BlockedDateRange(b.getBookingId(), b.getFrom(), b.getTo(), b.getReason()))
                .toList();

        return new AvailabilityResponse(
                document.getToyId(),
                document.getToyName(),
                document.getStatus(),
                available,
                blockedDates,
                document.getNextAvailable(),
                document.getLastUpdated()
        );
    }

}
