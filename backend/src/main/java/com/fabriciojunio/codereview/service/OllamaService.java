package com.fabriciojunio.codereview.service;

import com.fabriciojunio.codereview.dto.LlmAnalysisResult;
import com.fabriciojunio.codereview.dto.OllamaRequest;
import com.fabriciojunio.codereview.dto.OllamaResponse;
import com.fabriciojunio.codereview.exception.OllamaUnavailableException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;

@Service
@Slf4j
public class OllamaService {

    private static final int MAX_PARSE_RETRIES = 3;

    private final WebClient ollamaClient;
    private final ObjectMapper objectMapper;
    private final String model;
    private final int timeoutMinutes;

    public OllamaService(
            @Qualifier("ollamaWebClient") WebClient ollamaClient,
            ObjectMapper objectMapper,
            @Value("${ollama.model:codellama}") String model,
            @Value("${ollama.timeout-minutes:5}") int timeoutMinutes) {
        this.ollamaClient = ollamaClient;
        this.objectMapper = objectMapper;
        this.model = model;
        this.timeoutMinutes = timeoutMinutes;
    }

    /**
     * Sends a prompt to Ollama and parses the response as a structured JSON analysis.
     * Retries parsing up to MAX_PARSE_RETRIES times if the LLM returns invalid JSON.
     */
    public LlmAnalysisResult analyze(String prompt) {
        for (int attempt = 1; attempt <= MAX_PARSE_RETRIES; attempt++) {
            try {
                String rawResponse = callOllama(prompt);
                return objectMapper.readValue(rawResponse, LlmAnalysisResult.class);
            } catch (JsonProcessingException e) {
                log.warn("LLM response parse failed on attempt {}/{}: {}", attempt, MAX_PARSE_RETRIES, e.getMessage());
                if (attempt == MAX_PARSE_RETRIES) {
                    throw new OllamaUnavailableException("LLM returned invalid JSON after " + MAX_PARSE_RETRIES + " attempts");
                }
            }
        }
        // Unreachable, but required for compilation
        throw new OllamaUnavailableException("Unexpected state in analyze loop");
    }

    private String callOllama(String prompt) {
        OllamaRequest request = OllamaRequest.jsonMode(model, prompt);
        return ollamaClient.post()
                .uri("/api/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(OllamaResponse.class)
                .map(OllamaResponse::response)
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(2))
                        .maxBackoff(Duration.ofSeconds(30))
                        .filter(t -> !(t instanceof OllamaUnavailableException))
                        .doBeforeRetry(rs -> log.warn("Retrying Ollama call, attempt {}/{}", rs.totalRetries() + 1, 3)))
                // Catches both direct errors and errors wrapped in RetryExhaustedException after retries
                .onErrorMap(e -> !(e instanceof OllamaUnavailableException),
                        e -> new OllamaUnavailableException("Ollama call failed: " + e.getMessage()))
                .block(Duration.ofMinutes(timeoutMinutes));
    }

    /**
     * Streams the LLM response token by token via Flux for use in SSE endpoints.
     * Each emitted String is a partial token from the model.
     */
    public Flux<String> streamAnalysis(String prompt) {
        OllamaRequest request = new OllamaRequest(model, prompt, true, null);
        return ollamaClient.post()
                .uri("/api/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(OllamaResponse.class)
                .filter(r -> r.response() != null && !r.response().isEmpty())
                .map(OllamaResponse::response)
                .onErrorMap(e -> !(e instanceof OllamaUnavailableException),
                        e -> new OllamaUnavailableException("Streaming failed: " + e.getMessage()));
    }
}
