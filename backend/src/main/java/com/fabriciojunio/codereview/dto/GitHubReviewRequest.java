package com.fabriciojunio.codereview.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record GitHubReviewRequest(
        @NotBlank
        @Pattern(
                regexp = "^https://github\\.com/.+/.+/blob/.+\\.(java|py|js)$",
                message = "Must be a valid GitHub file URL ending in .java, .py, or .js"
        )
        String githubUrl
) {}
