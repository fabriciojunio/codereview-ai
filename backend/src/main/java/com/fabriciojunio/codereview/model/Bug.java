package com.fabriciojunio.codereview.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Bug(
        Integer line,
        String severity,
        String description,
        String suggestion
) {}
