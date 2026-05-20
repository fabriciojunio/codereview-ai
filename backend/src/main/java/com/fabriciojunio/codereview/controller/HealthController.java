package com.fabriciojunio.codereview.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Health", description = "Application health")
public class HealthController {

    @GetMapping("/ping")
    @Operation(summary = "Simple liveness check")
    public Map<String, String> ping() {
        return Map.of("status", "ok", "service", "codereview-ai");
    }
}
