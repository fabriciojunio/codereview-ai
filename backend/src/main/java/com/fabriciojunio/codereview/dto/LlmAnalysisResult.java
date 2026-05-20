package com.fabriciojunio.codereview.dto;

import com.fabriciojunio.codereview.model.Bug;
import com.fabriciojunio.codereview.model.CodeSmell;
import com.fabriciojunio.codereview.model.SolidViolation;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Represents the structured JSON response expected from the LLM.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LlmAnalysisResult(
        int score,
        String summary,
        List<Bug> bugs,
        @JsonProperty("code_smells") List<CodeSmell> codeSmells,
        @JsonProperty("solid_violations") List<SolidViolation> solidViolations,
        @JsonProperty("refactoring_suggestions") List<String> refactoringSuggestions,
        @JsonProperty("positive_aspects") List<String> positiveAspects
) {}
