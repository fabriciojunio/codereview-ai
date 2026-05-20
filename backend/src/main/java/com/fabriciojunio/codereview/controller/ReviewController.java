package com.fabriciojunio.codereview.controller;

import com.fabriciojunio.codereview.dto.GitHubReviewRequest;
import com.fabriciojunio.codereview.dto.ReviewRequest;
import com.fabriciojunio.codereview.dto.ReviewResponse;
import com.fabriciojunio.codereview.model.Review.Language;
import com.fabriciojunio.codereview.service.GitHubFetcher;
import com.fabriciojunio.codereview.service.OllamaService;
import com.fabriciojunio.codereview.service.PromptBuilder;
import com.fabriciojunio.codereview.service.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reviews")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Reviews", description = "Code review submission and retrieval")
@SecurityRequirement(name = "bearerAuth")
public class ReviewController {

    private final ReviewService reviewService;
    private final GitHubFetcher gitHubFetcher;
    private final OllamaService ollamaService;
    private final PromptBuilder promptBuilder;

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Submit code for review (text)")
    public ReviewResponse submit(@Valid @RequestBody ReviewRequest request,
                                 @AuthenticationPrincipal UserDetails principal) {
        return reviewService.submit(request, principal.getUsername());
    }

    @PostMapping("/upload")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Submit code for review (file upload)")
    public ReviewResponse submitFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("language") Language language,
            @AuthenticationPrincipal UserDetails principal) throws IOException {

        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        String filename = file.getOriginalFilename();
        return reviewService.submitFile(content, language, filename, principal.getUsername());
    }

    @PostMapping("/github")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Submit code for review (GitHub URL)")
    public ReviewResponse submitGitHub(
            @Valid @RequestBody GitHubReviewRequest request,
            @AuthenticationPrincipal UserDetails principal) {

        GitHubFetcher.FetchResult fetched = gitHubFetcher.fetchFile(request.githubUrl());
        return reviewService.submitGitHub(
                fetched.sourceCode(), fetched.language(), fetched.filename(),
                request.githubUrl(), principal.getUsername());
    }

    @GetMapping("/{ticketId}")
    @Operation(summary = "Get review result by ticket ID")
    public ReviewResponse getResult(@PathVariable UUID ticketId,
                                    @AuthenticationPrincipal UserDetails principal) {
        return reviewService.getResult(ticketId, principal.getUsername());
    }

    /**
     * Streams the LLM analysis in real-time via Server-Sent Events.
     * Fetches the original source code of the review and re-runs analysis
     * through Ollama in streaming mode so the client sees tokens as they arrive.
     */
    @GetMapping(value = "/{ticketId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Stream review analysis via SSE (real-time token streaming)")
    public Flux<ServerSentEvent<String>> streamResult(
            @PathVariable UUID ticketId,
            @AuthenticationPrincipal UserDetails principal) {

        String userEmail = principal.getUsername();
        String sourceCode = reviewService.getSourceCode(ticketId, userEmail);
        Language language = reviewService.getLanguage(ticketId, userEmail);
        String prompt = promptBuilder.buildPrompt(sourceCode, language);

        log.info("Starting SSE stream for review {} ({})", ticketId, language);

        return ollamaService.streamAnalysis(prompt)
                .map(token -> ServerSentEvent.<String>builder()
                        .event("token")
                        .data(token)
                        .build())
                .concatWith(Flux.just(ServerSentEvent.<String>builder()
                        .event("done")
                        .data("[DONE]")
                        .build()))
                .doOnError(e -> log.error("SSE stream error for review {}: {}", ticketId, e.getMessage()))
                .timeout(Duration.ofMinutes(10));
    }

    @GetMapping("/history")
    @Operation(summary = "Get paginated review history for authenticated user")
    public Page<ReviewResponse> getHistory(
            @AuthenticationPrincipal UserDetails principal,
            @PageableDefault(size = 10) Pageable pageable) {
        return reviewService.getHistory(principal.getUsername(), pageable);
    }
}
