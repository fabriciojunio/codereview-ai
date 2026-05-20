package com.fabriciojunio.codereview.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OllamaResponse(
        String model,
        String response,
        boolean done,
        @JsonProperty("total_duration") Long totalDuration,
        @JsonProperty("eval_count") Integer evalCount
) {}
