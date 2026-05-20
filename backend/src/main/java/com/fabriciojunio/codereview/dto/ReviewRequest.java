package com.fabriciojunio.codereview.dto;

import com.fabriciojunio.codereview.model.Review.Language;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReviewRequest(
        @NotBlank(message = "Source code is required")
        @Size(max = 50000, message = "Source code must not exceed 50000 characters")
        String sourceCode,

        @NotNull(message = "Language is required")
        Language language,

        String filename
) {}
