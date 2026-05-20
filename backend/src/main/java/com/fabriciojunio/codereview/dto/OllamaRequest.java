package com.fabriciojunio.codereview.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record OllamaRequest(
        String model,
        String prompt,
        boolean stream,
        @JsonProperty("format") String format
) {
    public static OllamaRequest jsonMode(String model, String prompt) {
        return new OllamaRequest(model, prompt, false, "json");
    }
}
