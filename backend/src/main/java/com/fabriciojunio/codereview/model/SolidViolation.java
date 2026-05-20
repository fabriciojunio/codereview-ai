package com.fabriciojunio.codereview.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SolidViolation(
        String principle,
        String description,
        String suggestion
) {}
