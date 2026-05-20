package com.fabriciojunio.codereview.service;

import com.fabriciojunio.codereview.model.Review.Language;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class PromptBuilderTest {

    private PromptBuilder promptBuilder;

    @BeforeEach
    void setUp() {
        promptBuilder = new PromptBuilder();
    }

    @ParameterizedTest
    @EnumSource(Language.class)
    void buildPrompt_containsSourceCode(Language language) {
        String sourceCode = "public class Hello { }";
        String prompt = promptBuilder.buildPrompt(sourceCode, language);
        assertThat(prompt).contains(sourceCode);
    }

    @Test
    void buildPrompt_javaContainsJavaSpecificInstructions() {
        String prompt = promptBuilder.buildPrompt("code", Language.java);
        assertThat(prompt).containsIgnoringCase("java");
    }

    @Test
    void buildPrompt_pythonContainsPythonSpecificInstructions() {
        String prompt = promptBuilder.buildPrompt("code", Language.python);
        assertThat(prompt).containsIgnoringCase("python");
    }

    @Test
    void buildPrompt_javascriptContainsJsSpecificInstructions() {
        String prompt = promptBuilder.buildPrompt("code", Language.javascript);
        assertThat(prompt).containsIgnoringCase("javascript");
    }

    @Test
    void buildPrompt_alwaysContainsJsonInstructions() {
        String prompt = promptBuilder.buildPrompt("code", Language.java);
        assertThat(prompt).contains("JSON");
        assertThat(prompt).contains("score");
        assertThat(prompt).contains("bugs");
    }
}
