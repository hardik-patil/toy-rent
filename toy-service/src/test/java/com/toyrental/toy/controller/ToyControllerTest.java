package com.toyrental.toy.controller;

import com.toyrental.toy.dto.ToyResponse;
import com.toyrental.toy.entity.ToyCondition;
import com.toyrental.toy.entity.ToyStatus;
import com.toyrental.toy.exception.ToyNotFoundException;
import com.toyrental.toy.service.ToyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ToyController.class)
@AutoConfigureMockMvc(addFilters = false)
class ToyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ToyService toyService;

    private ToyResponse sampleToy() {
        return new ToyResponse("toy-042", "LEGO Technic 42155", "desc", "LEGO", "Building Sets",
                "8-12", ToyCondition.GOOD, ToyStatus.AVAILABLE, BigDecimal.valueOf(5999),
                BigDecimal.valueOf(299), BigDecimal.valueOf(899), BigDecimal.valueOf(1500), true,
                List.of(), LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    void browseReturnsPagedCatalogue() throws Exception {
        Page<ToyResponse> page = new PageImpl<>(List.of(sampleToy()), PageRequest.of(0, 20), 1);
        when(toyService.browse(any(), any(), any())).thenReturn(page);

        mockMvc.perform(get("/api/v1/toys"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value("toy-042"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void getByIdReturnsToy() throws Exception {
        when(toyService.getById("toy-042")).thenReturn(sampleToy());

        mockMvc.perform(get("/api/v1/toys/toy-042"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("LEGO Technic 42155"));
    }

    @Test
    void getByIdReturns404WhenMissing() throws Exception {
        when(toyService.getById("toy-999")).thenThrow(new ToyNotFoundException("toy-999"));

        mockMvc.perform(get("/api/v1/toys/toy-999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TOY_NOT_FOUND"));
    }

    @Test
    void searchReturnsMatches() throws Exception {
        Page<ToyResponse> page = new PageImpl<>(List.of(sampleToy()), PageRequest.of(0, 20), 1);
        when(toyService.search(anyString(), any(), any(), any())).thenReturn(page);

        mockMvc.perform(get("/api/v1/toys/search").param("q", "lego"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].brand").value("LEGO"));
    }

    @Test
    void categoriesReturnsDistinctList() throws Exception {
        when(toyService.getCategories()).thenReturn(List.of("Building Sets", "Dolls"));

        mockMvc.perform(get("/api/v1/toys/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("Building Sets"))
                .andExpect(jsonPath("$[1]").value("Dolls"));
    }

}
