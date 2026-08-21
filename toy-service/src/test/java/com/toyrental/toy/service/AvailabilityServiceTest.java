package com.toyrental.toy.service;

import com.toyrental.toy.couchbase.CouchbaseAvailabilityRepository;
import com.toyrental.toy.couchbase.ToyAvailabilityDocument;
import com.toyrental.toy.dto.AvailabilityResponse;
import com.toyrental.toy.entity.AvailabilityAction;
import com.toyrental.toy.entity.Toy;
import com.toyrental.toy.entity.ToyCondition;
import com.toyrental.toy.entity.ToyStatus;
import com.toyrental.toy.exception.ToyNotFoundException;
import com.toyrental.toy.repository.ToyAvailabilityLogRepository;
import com.toyrental.toy.repository.ToyRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AvailabilityServiceTest {

    @Mock
    private ToyRepository toyRepository;
    @Mock
    private ToyService toyService;
    @Mock
    private CouchbaseAvailabilityRepository availabilityRepository;
    @Mock
    private ToyAvailabilityLogRepository availabilityLogRepository;
    @Mock
    private LogicalDateService logicalDateService;

    private AvailabilityService availabilityService;

    private Toy toy;

    @BeforeEach
    void setUp() {
        availabilityService = new AvailabilityService(toyRepository, toyService, availabilityRepository,
                availabilityLogRepository, logicalDateService, new SimpleMeterRegistry());

        toy = Toy.builder()
                .id("toy-042")
                .name("LEGO Technic 42155")
                .category("Building Sets")
                .ageGroup("8-12")
                .condition(ToyCondition.GOOD)
                .status(ToyStatus.AVAILABLE)
                .mrp(BigDecimal.valueOf(5999))
                .weeklyPrice(BigDecimal.valueOf(299))
                .monthlyPrice(BigDecimal.valueOf(899))
                .depositAmount(BigDecimal.valueOf(1500))
                .active(true)
                .build();
    }

    @Test
    void checkAvailabilityThrowsWhenToyMissing() {
        when(toyRepository.findByIdAndActiveTrue("toy-999")).thenReturn(Optional.empty());

        assertThrows(ToyNotFoundException.class,
                () -> availabilityService.checkAvailability("toy-999", LocalDate.now(), LocalDate.now().plusDays(1)));
    }

    @Test
    void checkAvailabilityIsTrueWhenNoCouchbaseDocumentExists() {
        when(toyRepository.findByIdAndActiveTrue("toy-042")).thenReturn(Optional.of(toy));
        when(availabilityRepository.findByToyId("toy-042")).thenReturn(Optional.empty());
        when(logicalDateService.getCurrentDate()).thenReturn(LocalDate.of(2025, 8, 1));

        AvailabilityResponse response = availabilityService.checkAvailability(
                "toy-042", LocalDate.of(2025, 8, 5), LocalDate.of(2025, 8, 10));

        assertThat(response.available()).isTrue();
        assertThat(response.blockedDates()).isEmpty();
    }

    @Test
    void checkAvailabilityIsFalseWhenRequestedRangeOverlapsABlockedDate() {
        ToyAvailabilityDocument document = ToyAvailabilityDocument.builder()
                .id("avail::toy-042")
                .toyId("toy-042")
                .toyName(toy.getName())
                .status("AVAILABLE")
                .blockedDates(java.util.List.of(ToyAvailabilityDocument.BlockedDate.builder()
                        .bookingId("bkg-001")
                        .from(LocalDate.of(2025, 8, 1))
                        .to(LocalDate.of(2025, 8, 7))
                        .reason("BOOKING")
                        .build()))
                .nextAvailable(LocalDate.of(2025, 8, 8))
                .lastUpdated(java.time.Instant.now())
                .build();

        when(toyRepository.findByIdAndActiveTrue("toy-042")).thenReturn(Optional.of(toy));
        when(availabilityRepository.findByToyId("toy-042")).thenReturn(Optional.of(document));

        AvailabilityResponse response = availabilityService.checkAvailability(
                "toy-042", LocalDate.of(2025, 8, 5), LocalDate.of(2025, 8, 12));

        assertThat(response.available()).isFalse();
    }

    @Test
    void blockDatesUpsertsCouchbaseDocumentAndWritesAuditLog() {
        when(toyRepository.findByIdAndActiveTrue("toy-042")).thenReturn(Optional.of(toy));
        when(availabilityRepository.findByToyId("toy-042")).thenReturn(Optional.empty());
        when(logicalDateService.getCurrentDate()).thenReturn(LocalDate.of(2025, 8, 1));

        availabilityService.blockDates("toy-042", "bkg-001", LocalDate.of(2025, 8, 1), LocalDate.of(2025, 8, 7));

        ArgumentCaptor<ToyAvailabilityDocument> docCaptor = ArgumentCaptor.forClass(ToyAvailabilityDocument.class);
        verify(availabilityRepository).save(docCaptor.capture());
        assertThat(docCaptor.getValue().getBlockedDates()).hasSize(1);
        assertThat(docCaptor.getValue().getNextAvailable()).isEqualTo(LocalDate.of(2025, 8, 8));

        verify(availabilityLogRepository).save(argThatLogHasAction(AvailabilityAction.BLOCKED));
    }

    @Test
    void blockDatesReplacesAnyExistingRangeForTheSameBooking() {
        ToyAvailabilityDocument existing = ToyAvailabilityDocument.builder()
                .id("avail::toy-042")
                .toyId("toy-042")
                .toyName(toy.getName())
                .status("AVAILABLE")
                .blockedDates(new java.util.ArrayList<>(java.util.List.of(ToyAvailabilityDocument.BlockedDate.builder()
                        .bookingId("bkg-001")
                        .from(LocalDate.of(2025, 8, 1))
                        .to(LocalDate.of(2025, 8, 5))
                        .reason("BOOKING")
                        .build())))
                .nextAvailable(LocalDate.of(2025, 8, 6))
                .lastUpdated(java.time.Instant.now())
                .build();

        when(toyRepository.findByIdAndActiveTrue("toy-042")).thenReturn(Optional.of(toy));
        when(availabilityRepository.findByToyId("toy-042")).thenReturn(Optional.of(existing));
        when(logicalDateService.getCurrentDate()).thenReturn(LocalDate.of(2025, 8, 1));

        availabilityService.blockDates("toy-042", "bkg-001", LocalDate.of(2025, 8, 10), LocalDate.of(2025, 8, 14));

        ArgumentCaptor<ToyAvailabilityDocument> docCaptor = ArgumentCaptor.forClass(ToyAvailabilityDocument.class);
        verify(availabilityRepository).save(docCaptor.capture());
        assertThat(docCaptor.getValue().getBlockedDates()).hasSize(1);
        assertThat(docCaptor.getValue().getBlockedDates().get(0).getFrom()).isEqualTo(LocalDate.of(2025, 8, 10));
    }

    @Test
    void releaseDatesRemovesTheBookingsRangeAndWritesAuditLog() {
        ToyAvailabilityDocument existing = ToyAvailabilityDocument.builder()
                .id("avail::toy-042")
                .toyId("toy-042")
                .toyName(toy.getName())
                .status("AVAILABLE")
                .blockedDates(new java.util.ArrayList<>(java.util.List.of(ToyAvailabilityDocument.BlockedDate.builder()
                        .bookingId("bkg-001")
                        .from(LocalDate.of(2025, 8, 1))
                        .to(LocalDate.of(2025, 8, 7))
                        .reason("BOOKING")
                        .build())))
                .nextAvailable(LocalDate.of(2025, 8, 8))
                .lastUpdated(java.time.Instant.now())
                .build();

        when(toyRepository.findByIdAndActiveTrue("toy-042")).thenReturn(Optional.of(toy));
        when(availabilityRepository.findByToyId("toy-042")).thenReturn(Optional.of(existing));
        when(logicalDateService.getCurrentDate()).thenReturn(LocalDate.of(2025, 8, 1));

        availabilityService.releaseDates("toy-042", "bkg-001");

        ArgumentCaptor<ToyAvailabilityDocument> docCaptor = ArgumentCaptor.forClass(ToyAvailabilityDocument.class);
        verify(availabilityRepository).save(docCaptor.capture());
        assertThat(docCaptor.getValue().getBlockedDates()).isEmpty();
        assertThat(docCaptor.getValue().getNextAvailable()).isEqualTo(LocalDate.of(2025, 8, 1));

        verify(availabilityLogRepository).save(argThatLogHasAction(AvailabilityAction.RELEASED));
        verify(availabilityRepository, times(1)).save(any());
        verify(toyRepository, never()).save(any());
    }

    private com.toyrental.toy.entity.ToyAvailabilityLog argThatLogHasAction(AvailabilityAction action) {
        return org.mockito.ArgumentMatchers.argThat(log -> log.getAction() == action);
    }

}
