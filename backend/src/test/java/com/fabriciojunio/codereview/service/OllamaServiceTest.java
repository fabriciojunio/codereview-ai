package com.fabriciojunio.codereview.service;

import com.fabriciojunio.codereview.dto.LlmAnalysisResult;
import com.fabriciojunio.codereview.dto.OllamaResponse;
import com.fabriciojunio.codereview.exception.OllamaUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OllamaServiceTest {

    private MockWebServer mockWebServer;
    private OllamaService ollamaService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        WebClient webClient = WebClient.builder()
                .baseUrl(mockWebServer.url("/").toString())
                .build();

        ollamaService = new OllamaService(webClient, objectMapper, "codellama", 5);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    void analyze_successfulResponse_returnsAnalysis() throws Exception {
        String validJson = """
                {
                  "score": 75,
                  "summary": "Good code overall",
                  "bugs": [],
                  "code_smells": [],
                  "solid_violations": [],
                  "refactoring_suggestions": ["Extract method"],
                  "positive_aspects": ["Good naming"]
                }
                """;

        OllamaResponse ollamaResponse = new OllamaResponse("codellama", validJson, true, 1000L, 100);
        mockWebServer.enqueue(new MockResponse()
                .setBody(objectMapper.writeValueAsString(ollamaResponse))
                .addHeader("Content-Type", "application/json"));

        LlmAnalysisResult result = ollamaService.analyze("test prompt");

        assertThat(result.score()).isEqualTo(75);
        assertThat(result.summary()).isEqualTo("Good code overall");
        assertThat(result.refactoringSuggestions()).contains("Extract method");
    }

    @Test
    void analyze_invalidJsonResponse_throwsOllamaUnavailableException() throws Exception {
        OllamaResponse badResponse = new OllamaResponse("codellama", "not valid json at all", true, 1000L, 50);

        // Enqueue 3 retries
        for (int i = 0; i < 3; i++) {
            mockWebServer.enqueue(new MockResponse()
                    .setBody(objectMapper.writeValueAsString(badResponse))
                    .addHeader("Content-Type", "application/json"));
        }

        assertThatThrownBy(() -> ollamaService.analyze("test prompt"))
                .isInstanceOf(OllamaUnavailableException.class)
                .hasMessageContaining("invalid JSON");
    }

    @Test
    void analyze_serverError_throwsOllamaUnavailableException() {
        // Retry.backoff(3,...) = 3 retries = 4 total HTTP calls
        mockWebServer.enqueue(new MockResponse().setResponseCode(500));
        mockWebServer.enqueue(new MockResponse().setResponseCode(500));
        mockWebServer.enqueue(new MockResponse().setResponseCode(500));
        mockWebServer.enqueue(new MockResponse().setResponseCode(500));

        assertThatThrownBy(() -> ollamaService.analyze("test prompt"))
                .isInstanceOf(OllamaUnavailableException.class)
                .hasMessageContaining("Ollama call failed");
    }
}
