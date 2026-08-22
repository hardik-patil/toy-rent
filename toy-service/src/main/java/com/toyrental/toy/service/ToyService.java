package com.toyrental.toy.service;

import com.toyrental.toy.dto.ToyMetadataResponse;
import com.toyrental.toy.dto.ToyRequest;
import com.toyrental.toy.dto.ToyResponse;
import com.toyrental.toy.entity.Toy;
import com.toyrental.toy.entity.ToyCondition;
import com.toyrental.toy.entity.ToyImage;
import com.toyrental.toy.entity.ToyStatus;
import com.toyrental.toy.exception.ToyNotFoundException;
import com.toyrental.toy.repository.ToyImageRepository;
import com.toyrental.toy.repository.ToyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.toyrental.toy.util.IdGenerator.shortId;

@Slf4j
@Service
public class ToyService {

    private final ToyRepository toyRepository;
    private final ToyImageRepository toyImageRepository;

    public ToyService(ToyRepository toyRepository, ToyImageRepository toyImageRepository) {
        this.toyRepository = toyRepository;
        this.toyImageRepository = toyImageRepository;
    }

    @Transactional(readOnly = true)
    public Page<ToyResponse> browse(String category, String ageGroup, Pageable pageable) {
        return toEnrichedPage(toyRepository.browse(category, ageGroup, pageable));
    }

    @Transactional(readOnly = true)
    public Page<ToyResponse> search(String query, String category, String ageGroup, Pageable pageable) {
        return toEnrichedPage(toyRepository.search(query, category, ageGroup, pageable));
    }

    @Transactional(readOnly = true)
    public ToyResponse getById(String toyId) {
        Toy toy = toyRepository.findByIdAndActiveTrue(toyId)
                .orElseThrow(() -> new ToyNotFoundException(toyId));
        return toResponse(toy);
    }

    @Transactional(readOnly = true)
    public List<String> getCategories() {
        return toyRepository.findDistinctCategories();
    }

    @Transactional(readOnly = true)
    public ToyMetadataResponse getMetadata() {
        return ToyMetadataResponse.of(toyRepository.findDistinctCategories(), toyRepository.findDistinctAgeGroups());
    }

    @Transactional(readOnly = true)
    public Page<ToyResponse> getInventory(Pageable pageable) {
        return toEnrichedPage(toyRepository.findByActiveTrue(pageable));
    }

    @Transactional(readOnly = true)
    public Page<ToyResponse> getLowStock(Pageable pageable) {
        return toEnrichedPage(toyRepository.findByActiveTrueAndStatusNot(ToyStatus.AVAILABLE, pageable));
    }

    @Transactional
    public ToyResponse create(ToyRequest request) {
        Toy toy = Toy.builder()
                .id(shortId("toy"))
                .name(request.name())
                .description(request.description())
                .brand(request.brand())
                .category(request.category())
                .ageGroup(request.ageGroup())
                .condition(request.condition())
                .status(request.status() != null ? request.status() : ToyStatus.AVAILABLE)
                .mrp(request.mrp())
                .weeklyPrice(request.weeklyPrice())
                .monthlyPrice(request.monthlyPrice())
                .depositAmount(request.depositAmount())
                .active(true)
                .build();
        // saveAndFlush, not save: createdAt/updatedAt are populated by Hibernate's
        // @CreationTimestamp/@UpdateTimestamp generators only when the INSERT is actually flushed
        // to the DB, which a plain save() inside @Transactional defers until commit — reading them
        // back immediately afterward (toResponse below) would otherwise see null.
        Toy saved = toyRepository.saveAndFlush(toy);
        log.info("Created toy id={}", saved.getId());
        return toResponse(saved);
    }

    @Transactional
    public ToyResponse update(String toyId, ToyRequest request) {
        Toy toy = toyRepository.findByIdAndActiveTrue(toyId)
                .orElseThrow(() -> new ToyNotFoundException(toyId));

        toy.setName(request.name());
        toy.setDescription(request.description());
        toy.setBrand(request.brand());
        toy.setCategory(request.category());
        toy.setAgeGroup(request.ageGroup());
        toy.setCondition(request.condition());
        if (request.status() != null) {
            toy.setStatus(request.status());
        }
        toy.setMrp(request.mrp());
        toy.setWeeklyPrice(request.weeklyPrice());
        toy.setMonthlyPrice(request.monthlyPrice());
        toy.setDepositAmount(request.depositAmount());

        Toy saved = toyRepository.saveAndFlush(toy);
        log.info("Updated toy id={}", saved.getId());
        return toResponse(saved);
    }

    @Transactional
    public void softDelete(String toyId) {
        Toy toy = toyRepository.findByIdAndActiveTrue(toyId)
                .orElseThrow(() -> new ToyNotFoundException(toyId));
        toy.setActive(false);
        toyRepository.save(toy);
        log.info("Soft-deleted toy id={}", toyId);
    }

    @Transactional
    public ToyResponse updateCondition(String toyId, ToyCondition condition) {
        Toy toy = toyRepository.findByIdAndActiveTrue(toyId)
                .orElseThrow(() -> new ToyNotFoundException(toyId));
        toy.setCondition(condition);
        Toy saved = toyRepository.saveAndFlush(toy);
        log.info("Updated condition for toy id={} condition={}", toyId, condition);
        return toResponse(saved);
    }

    @Transactional
    public ToyResponse.ToyImageResponse addImage(String toyId, String url, boolean primary, int sortOrder) {
        toyRepository.findByIdAndActiveTrue(toyId)
                .orElseThrow(() -> new ToyNotFoundException(toyId));
        ToyImage image = ToyImage.builder()
                .id(UUID.randomUUID().toString())
                .toyId(toyId)
                .url(url)
                .primary(primary)
                .sortOrder(sortOrder)
                .build();
        ToyImage saved = toyImageRepository.save(image);
        log.info("Added image id={} for toy id={}", saved.getId(), toyId);
        return ToyResponse.ToyImageResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<ToyResponse> toResponses(List<Toy> toys) {
        Map<String, List<ToyResponse.ToyImageResponse>> imagesByToyId = imagesByToyId(toys);
        return toys.stream()
                .map(toy -> ToyResponse.from(toy, imagesByToyId.getOrDefault(toy.getId(), List.of())))
                .toList();
    }

    private Page<ToyResponse> toEnrichedPage(Page<Toy> page) {
        Map<String, List<ToyResponse.ToyImageResponse>> imagesByToyId = imagesByToyId(page.getContent());
        return page.map(toy -> ToyResponse.from(toy, imagesByToyId.getOrDefault(toy.getId(), List.of())));
    }

    private Map<String, List<ToyResponse.ToyImageResponse>> imagesByToyId(List<Toy> toys) {
        List<String> toyIds = toys.stream().map(Toy::getId).toList();
        return toyImageRepository.findByToyIdInOrderBySortOrderAsc(toyIds).stream()
                .collect(Collectors.groupingBy(ToyImage::getToyId,
                        Collectors.mapping(ToyResponse.ToyImageResponse::from, Collectors.toList())));
    }

    private ToyResponse toResponse(Toy toy) {
        List<ToyResponse.ToyImageResponse> images = toyImageRepository.findByToyIdOrderBySortOrderAsc(toy.getId())
                .stream()
                .map(ToyResponse.ToyImageResponse::from)
                .toList();
        return ToyResponse.from(toy, images);
    }

}
