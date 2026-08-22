package com.toyrental.toy.controller;

import com.toyrental.toy.dto.PagedResponse;
import com.toyrental.toy.dto.ToyMetadataResponse;
import com.toyrental.toy.dto.ToyResponse;
import com.toyrental.toy.service.ToyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Toys", description = "Toy catalogue browsing and search")
@RestController
@RequestMapping("/api/v1/toys")
public class ToyController {

    private final ToyService toyService;

    public ToyController(ToyService toyService) {
        this.toyService = toyService;
    }

    @Operation(summary = "Paginated toy catalogue")
    @GetMapping
    public PagedResponse<ToyResponse> browse(@RequestParam(required = false) String category,
                                              @RequestParam(required = false) String ageGroup,
                                              Pageable pageable) {
        return PagedResponse.from(toyService.browse(category, ageGroup, pageable));
    }

    @Operation(summary = "Toy detail")
    @GetMapping("/{toyId}")
    public ToyResponse getById(@PathVariable String toyId) {
        return toyService.getById(toyId);
    }

    @Operation(summary = "Search toys by name/brand, optionally filtered by category/age group")
    @GetMapping("/search")
    public PagedResponse<ToyResponse> search(@RequestParam String q,
                                              @RequestParam(required = false) String category,
                                              @RequestParam(required = false) String age,
                                              Pageable pageable) {
        return PagedResponse.from(toyService.search(q, category, age, pageable));
    }

    @Operation(summary = "All distinct toy categories")
    @GetMapping("/categories")
    public List<String> categories() {
        return toyService.getCategories();
    }

    @Operation(summary = "Categories, age groups, conditions and statuses — for populating admin form dropdowns/radio groups")
    @GetMapping("/metadata")
    public ToyMetadataResponse metadata() {
        return toyService.getMetadata();
    }

}
