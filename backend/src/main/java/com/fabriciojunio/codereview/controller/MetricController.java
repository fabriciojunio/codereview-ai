package com.fabriciojunio.codereview.controller;

import com.fabriciojunio.codereview.dto.MetricResponse;
import com.fabriciojunio.codereview.service.MetricService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/metrics")
@RequiredArgsConstructor
@Tag(name = "Métricas", description = "Estatísticas de uso do LLM e cache")
public class MetricController {

    private final MetricService metricService;

    @GetMapping
    @Operation(summary = "Listar métricas do usuário autenticado")
    public Page<MetricResponse> myMetrics(
            @AuthenticationPrincipal UserDetails principal,
            @PageableDefault(size = 20) Pageable pageable) {
        return metricService.findByUserEmail(principal.getUsername(), pageable);
    }

    @GetMapping("/cache-hit-rate")
    @Operation(summary = "Taxa global de cache hit (%)")
    public ResponseEntity<Map<String, Double>> cacheHitRate() {
        return ResponseEntity.ok(Map.of("cacheHitRate", metricService.cacheHitRate()));
    }
}
