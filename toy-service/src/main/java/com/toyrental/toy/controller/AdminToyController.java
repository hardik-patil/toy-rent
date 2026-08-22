package com.toyrental.toy.controller;

import com.toyrental.toy.dto.PagedResponse;
import com.toyrental.toy.dto.ToyConditionRequest;
import com.toyrental.toy.dto.ToyImageRequest;
import com.toyrental.toy.dto.ToyRequest;
import com.toyrental.toy.dto.ToyResponse;
import com.toyrental.toy.service.MinioService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Toys Admin", description = "Admin-only toy catalogue management (requires ROLE_ADMIN)")
@RestController
@RequestMapping("/api/v1")
public class AdminToyController {

    private final ToyService toyService;
    private final MinioService minioService;

    public AdminToyController(ToyService toyService, MinioService minioService) {
        this.toyService = toyService;
        this.minioService = minioService;
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

    @Operation(summary = "Add an image for a toy by URL (image already hosted elsewhere)")
    @PostMapping("/toys/{toyId}/images")
    @ResponseStatus(HttpStatus.CREATED)
    public ToyResponse.ToyImageResponse addImage(@PathVariable String toyId,
                                                   @Valid @RequestBody ToyImageRequest request) {
        return toyService.addImage(toyId, request.url(), request.primary(), request.sortOrder());
    }

    @Operation(summary = "Upload a photo file for a toy (multipart) — stored in MinIO, publicly readable")
    @PostMapping(value = "/toys/{toyId}/images/upload", consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    public ToyResponse.ToyImageResponse uploadImage(@PathVariable String toyId,
                                                      @RequestParam("file") MultipartFile file,
                                                      @RequestParam(defaultValue = "false") boolean primary,
                                                      @RequestParam(defaultValue = "0") int sortOrder) {
        String url = minioService.uploadToyImage(toyId, file);
        return toyService.addImage(toyId, url, primary, sortOrder);
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
