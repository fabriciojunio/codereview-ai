package com.fabriciojunio.codereview.dto;

public record SummaryResponse(
    long totalReviews,
    long totalUsers,
    double cacheHitRate,
    String mostAnalyzedLanguage
) {}
