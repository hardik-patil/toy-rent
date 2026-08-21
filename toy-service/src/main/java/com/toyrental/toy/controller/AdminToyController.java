package com.toyrental.toy.controller;

import com.toyrental.toy.dto.PagedResponse;
import com.toyrental.toy.dto.ToyConditionRequest;
import com.toyrental.toy.dto.ToyImageRequest;
import com.toyrental.toy.dto.ToyRequest;
import com.toyrental.toy.dto.ToyResponse;
import com.toyrental.toy.service.ToyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Toys Admin", description = "Admin-only toy catalogue management (requires ROLE_ADMIN)")
@RestController
@RequestMapping("/api/v1")
public class AdminToyController {

    private final ToyService toyService;

    public AdminToyController(ToyService toyService) {
        this.toyService = toyService;
    }

    @Operation(summary = "Add a toy")
    @PostMapping("/toys")
    @ResponseStatus(HttpStatus.CREATED)
    public ToyResponse create(@Valid @RequestBody ToyRequest request) {
        return toyService.create(request);
    }

    @Operation(summary = "Update a toy")
    @PutMapping("/toys/{toyId}")
    public ToyResponse update(@PathVariable String toyId, @Valid @RequestBody ToyRequest request) {
        return toyService.update(toyId, request);
    }

    @Operation(summary = "Soft delete a toy")
    @DeleteMapping("/toys/{toyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String toyId) {
        toyService.softDelete(toyId);
    }

    @Operation(summary = "Upload an image for a toy")
    @PostMapping("/toys/{toyId}/images")
    @ResponseStatus(HttpStatus.CREATED)
    public ToyResponse.ToyImageResponse addImage(@PathVariable String toyId,
                                                   @Valid @RequestBody ToyImageRequest request) {
        return toyService.addImage(toyId, request.url(), request.primary(), request.sortOrder());
    }

    @Operation(summary = "Inventory status across all active toys")
    @GetMapping("/admin/toys/inventory")
    public PagedResponse<ToyResponse> inventory(Pageable pageable) {
        return PagedResponse.from(toyService.getInventory(pageable));
    }

    @Operation(summary = "Toys currently unavailable (rented, damaged, cleaning, retired)")
    @GetMapping("/admin/toys/low-stock")
    public PagedResponse<ToyResponse> lowStock(Pageable pageable) {
        return PagedResponse.from(toyService.getLowStock(pageable));
    }

    @Operation(summary = "Update a toy's condition")
    @PutMapping("/admin/toys/{toyId}/condition")
    public ToyResponse updateCondition(@PathVariable String toyId, @Valid @RequestBody ToyConditionRequest request) {
        return toyService.updateCondition(toyId, request.condition());
    }

}
