package com.fabriciojunio.codereview.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CodeSmell(
        Integer line,
        String type,
        String description,
        String suggestion
) {}
