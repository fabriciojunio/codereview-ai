package com.fabriciojunio.codereview.service;

import com.fabriciojunio.codereview.model.Review.Language;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

/**
 * Builds optimized, language-specific prompts for the LLM.
 * Uses the Strategy pattern: one prompt template per language.
 */
@Service
@Slf4j
public class PromptBuilder {

    private final Map<Language, String> promptTemplates = new EnumMap<>(Language.class);

    public PromptBuilder() {
        for (Language lang : Language.values()) {
            promptTemplates.put(lang, loadTemplate(lang));
        }
    }

    public String buildPrompt(String sourceCode, Language language) {
        if (sourceCode == null || sourceCode.isBlank()) {
            throw new IllegalArgumentException("Source code must not be null or blank");
        }
        String template = promptTemplates.getOrDefault(language, promptTemplates.get(Language.java));
        return template.replace("{{SOURCE_CODE}}", sourceCode);
    }

    private String loadTemplate(Language language) {
        String path = "prompts/" + language.name() + "_review_prompt.txt";
        try {
            ClassPathResource resource = new ClassPathResource(path);
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to load prompt template: {}", path, e);
            return buildFallbackPrompt();
        }
    }

    private String buildFallbackPrompt() {
        return """
                You are an expert code reviewer. Analyze the following code and respond with a valid JSON object only.

                Analyze for:
                - Potential bugs
                - Code smells
                - SOLID principle violations
                - Clean Code violations
                - Refactoring opportunities
                - Positive aspects
                - Overall quality score (0-100)

                Respond ONLY with this JSON structure:
                {
                  "score": <number 0-100>,
                  "summary": "<brief overall assessment>",
                  "bugs": [{"line": <number>, "severity": "<HIGH|MEDIUM|LOW>", "description": "<desc>", "suggestion": "<fix>"}],
                  "code_smells": [{"line": <number>, "type": "<smell type>", "description": "<desc>", "suggestion": "<fix>"}],
                  "solid_violations": [{"principle": "<SRP|OCP|LSP|ISP|DIP>", "description": "<desc>", "suggestion": "<fix>"}],
                  "refactoring_suggestions": ["<suggestion 1>", "<suggestion 2>"],
                  "positive_aspects": ["<positive 1>", "<positive 2>"]
                }

                Code to review:
                ```
                {{SOURCE_CODE}}
                ```
                """;
    }
}
