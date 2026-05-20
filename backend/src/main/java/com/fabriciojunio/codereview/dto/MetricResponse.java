package com.fabriciojunio.codereview.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record MetricResponse(
    UUID id,
    String language,
    String modelUsed,
    Integer tokensIn,
    Integer tokensOut,
    Integer durationMs,
    boolean cacheHit,
    OffsetDateTime createdAt
) {}
