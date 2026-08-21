package com.toyrental.toy.service;

import com.toyrental.toy.couchbase.CouchbaseAvailabilityRepository;
import com.toyrental.toy.couchbase.ToyAvailabilityDocument;
import com.toyrental.toy.couchbase.ToyAvailabilityDocument.BlockedDate;
import com.toyrental.toy.dto.AvailabilityResponse;
import com.toyrental.toy.dto.AvailabilityResponse.BlockedDateRange;
import com.toyrental.toy.dto.PagedResponse;
import com.toyrental.toy.dto.ToyResponse;
import com.toyrental.toy.entity.AvailabilityAction;
import com.toyrental.toy.entity.AvailabilityReason;
import com.toyrental.toy.entity.Toy;
import com.toyrental.toy.entity.ToyAvailabilityLog;
import com.toyrental.toy.entity.ToyStatus;
import com.toyrental.toy.exception.ToyNotFoundException;
import com.toyrental.toy.repository.ToyAvailabilityLogRepository;
import com.toyrental.toy.repository.ToyRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class AvailabilityService {

    private final ToyRepository toyRepository;
    private final ToyService toyService;
    private final CouchbaseAvailabilityRepository availabilityRepository;
    private final ToyAvailabilityLogRepository availabilityLogRepository;
    private final LogicalDateService logicalDateService;
    private final Counter cacheHitCounter;
    private final Counter cacheMissCounter;

    public AvailabilityService(ToyRepository toyRepository,
                                ToyService toyService,
                                CouchbaseAvailabilityRepository availabilityRepository,
                                ToyAvailabilityLogRepository availabilityLogRepository,
                                LogicalDateService logicalDateService,
                                MeterRegistry meterRegistry) {
        this.toyRepository = toyRepository;
        this.toyService = toyService;
        this.availabilityRepository = availabilityRepository;
        this.availabilityLogRepository = availabilityLogRepository;
        this.logicalDateService = logicalDateService;
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
        return toResponse(document, isAvailable(document, from, to));
    }

    @Transactional(readOnly = true)
    public AvailabilityResponse getCalendar(String toyId) {
        Toy toy = requireToy(toyId);
        ToyAvailabilityDocument document = loadOrDefault(toy);
        return toResponse(document, null);
    }

    /**
     * Browses toys the catalogue marks AVAILABLE and cross-checks each against Couchbase for the
     * requested date range. Filtering happens after the page is fetched, so totalElements/
     * totalPages reflect the unfiltered AVAILABLE-status page, not the exact date-filtered count —
     * acceptable for a browse endpoint at this scale; a precise count would need a dedicated
     * availability index rather than a per-toy Couchbase lookup per page.
     */
    @Transactional(readOnly = true)
    public PagedResponse<ToyResponse> browseAvailable(LocalDate from, LocalDate to, Pageable pageable) {
        Page<Toy> page = toyRepository.findByActiveTrueAndStatus(ToyStatus.AVAILABLE, pageable);
        List<Toy> available = page.getContent().stream()
                .filter(toy -> isAvailable(loadOrDefault(toy), from, to))
                .toList();

        return new PagedResponse<>(toyService.toResponses(available), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages(), page.isLast());
    }

    /**
     * Applied by the booking.confirmed Kafka consumer (and the internal manual-override endpoint)
     * once a booking is paid for. Idempotent by bookingId: replaying the same booking's block is
     * safe because any prior range for that bookingId is replaced rather than duplicated.
     */
    @Transactional
    public void blockDates(String toyId, String bookingId, LocalDate from, LocalDate to) {
        Toy toy = requireToy(toyId);
        ToyAvailabilityDocument document = loadOrDefault(toy);

        List<BlockedDate> blocked = new ArrayList<>(document.getBlockedDates());
        blocked.removeIf(b -> bookingId.equals(b.getBookingId()));
        blocked.add(BlockedDate.builder().bookingId(bookingId).from(from).to(to).reason("BOOKING").build());

        document.setBlockedDates(blocked);
        document.setNextAvailable(computeNextAvailable(blocked));
        document.setLastUpdated(Instant.now());
        availabilityRepository.save(document);

        availabilityLogRepository.save(ToyAvailabilityLog.builder()
                .id(UUID.randomUUID().toString())
                .toyId(toyId)
                .bookingId(bookingId)
                .blockedFrom(from)
                .blockedTo(to)
                .action(AvailabilityAction.BLOCKED)
                .reason(AvailabilityReason.BOOKING)
                .build());

        log.info("Blocked availability toyId={} bookingId={} from={} to={}", toyId, bookingId, from, to);
    }

    /** Applied by the booking.cancelled Kafka consumer once a booking is cancelled. */
    @Transactional
    public void releaseDates(String toyId, String bookingId) {
        Toy toy = requireToy(toyId);
        ToyAvailabilityDocument document = loadOrDefault(toy);

        List<BlockedDate> blocked = new ArrayList<>(document.getBlockedDates());
        Optional<BlockedDate> released = blocked.stream()
                .filter(b -> bookingId.equals(b.getBookingId()))
                .findFirst();
        blocked.removeIf(b -> bookingId.equals(b.getBookingId()));

        document.setBlockedDates(blocked);
        document.setNextAvailable(computeNextAvailable(blocked));
        document.setLastUpdated(Instant.now());
        availabilityRepository.save(document);

        LocalDate today = logicalDateService.getCurrentDate();
        availabilityLogRepository.save(ToyAvailabilityLog.builder()
                .id(UUID.randomUUID().toString())
                .toyId(toyId)
                .bookingId(bookingId)
                .blockedFrom(released.map(BlockedDate::getFrom).orElse(today))
                .blockedTo(released.map(BlockedDate::getTo).orElse(today))
                .action(AvailabilityAction.RELEASED)
                .reason(AvailabilityReason.BOOKING)
                .build());

        log.info("Released availability toyId={} bookingId={}", toyId, bookingId);
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
                .nextAvailable(logicalDateService.getCurrentDate())
                .lastUpdated(Instant.now())
                .build();
    }

    private boolean isAvailable(ToyAvailabilityDocument document, LocalDate from, LocalDate to) {
        return document.getBlockedDates().stream()
                .noneMatch(blocked -> overlaps(blocked.getFrom(), blocked.getTo(), from, to));
    }

    private boolean overlaps(LocalDate blockedFrom, LocalDate blockedTo, LocalDate from, LocalDate to) {
        return !blockedTo.isBefore(from) && !blockedFrom.isAfter(to);
    }

    private LocalDate computeNextAvailable(List<BlockedDate> blockedDates) {
        List<BlockedDate> sorted = blockedDates.stream()
                .sorted(Comparator.comparing(BlockedDate::getFrom))
                .toList();

        LocalDate candidate = logicalDateService.getCurrentDate();
        boolean advanced = true;
        while (advanced) {
            advanced = false;
            for (BlockedDate blocked : sorted) {
                if (!candidate.isBefore(blocked.getFrom()) && !candidate.isAfter(blocked.getTo())) {
                    candidate = blocked.getTo().plusDays(1);
                    advanced = true;
                }
            }
        }
        return candidate;
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
