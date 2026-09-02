package com.fabriciojunio.codereview.service;

import com.fabriciojunio.codereview.dto.LlmAnalysisResult;
import com.fabriciojunio.codereview.dto.ReviewResponse;
import com.fabriciojunio.codereview.exception.OllamaUnavailableException;
import com.fabriciojunio.codereview.model.Review;
import com.fabriciojunio.codereview.model.ReviewResult;
import com.fabriciojunio.codereview.repository.ReviewRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReviewProcessor {

    private final ReviewRepository reviewRepository;
    private final OllamaService ollamaService;
    private final PromptBuilder promptBuilder;
    private final CacheService cacheService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    /**
     * Quanto esperar por quem chegou antes, no total.
     *
     * <p>Seis tentativas de cinco segundos cobrem a inferência típica sem
     * segurar a mensagem perto do prazo de reentrega da fila. É configurável
     * porque o número certo depende do modelo e da máquina, e porque teste que
     * exercita espera de verdade fica lento à toa.
     */
    @Value("${codereview.cache.espera.tentativas:6}")
    int tentativasDeEspera = 6;

    @Value("${codereview.cache.espera.intervalo-ms:5000}")
    long intervaloDaEsperaMs = 5000;

    @Transactional
    public void process(UUID reviewId) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new IllegalArgumentException("Review not found: " + reviewId));

        review.setStatus(Review.ReviewStatus.PROCESSING);
        reviewRepository.save(review);

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            Optional<ReviewResponse> cached = cacheService.getCachedResult(
                    review.getSourceCode(), review.getLanguage().name());

            LlmAnalysisResult analysis;
            boolean fromCache = cached.isPresent();
            if (fromCache) {
                log.info("Cache hit for review {}, skipping LLM call", reviewId);
                analysis = convertCachedToAnalysis(cached.get());
            } else {
                analysis = analisarUmaVezSo(review);
            }

            ReviewResult result = buildResult(review, analysis);
            review.setResult(result);
            review.setStatus(Review.ReviewStatus.COMPLETED);
            review.setCompletedAt(Instant.now());
            Review saved = reviewRepository.save(review);

            // Cache only results obtained from the LLM (not those already from cache)
            if (!fromCache) {
                ReviewResponse responseToCache = buildResponseFromSaved(saved, analysis);
                cacheService.cacheResult(review.getSourceCode(), review.getLanguage().name(), responseToCache);
                // Liberar depois de gravar, e não antes: quem está esperando
                // precisa encontrar o resultado, e não a chave livre e o cache
                // ainda vazio.
                cacheService.releaseProcessing(review.getSourceCode(), review.getLanguage().name());
            }

            meterRegistry.counter("codereview.reviews.completed",
                    "language", review.getLanguage().name()).increment();

            log.info("Review {} completed with score {}", reviewId, analysis.score());
        } catch (OllamaUnavailableException e) {
            log.error("Ollama unavailable for review {}: {}", reviewId, e.getMessage());
            markFailed(review, "LLM service unavailable: " + e.getMessage());
            meterRegistry.counter("codereview.reviews.failed", "reason", "ollama_unavailable").increment();
        } catch (Exception e) {
            log.error("Unexpected error processing review {}", reviewId, e);
            markFailed(review, "Internal processing error");
            meterRegistry.counter("codereview.reviews.failed", "reason", "unexpected_error").increment();
        } finally {
            sample.stop(meterRegistry.timer("codereview.processing.time",
                    "language", review.getLanguage().name()));
        }
    }

    /**
     * Chama o modelo, mas só quando ninguém mais está chamando pelo mesmo código.
     *
     * <p>Dez submissões iguais caindo na fila juntas encontram o cache vazio e,
     * sem esta reserva, geram dez inferências para produzir a mesma resposta.
     * Cada uma custa dezenas de segundos de GPU.
     *
     * <p>Quem não consegue a reserva espera pouco e reconsulta, na aposta de
     * que quem está analisando termine primeiro. Se não terminar, analisa
     * também: a espera é uma economia, não uma trava. Preferir travar deixaria
     * a mensagem presa por causa de um processo que talvez tenha morrido.
     */
    private LlmAnalysisResult analisarUmaVezSo(Review review) {
        String codigo = review.getSourceCode();
        String linguagem = review.getLanguage().name();

        if (!cacheService.reserveProcessing(codigo, linguagem)) {
            Optional<ReviewResponse> deQuemChegouAntes = aguardarQuemEstaAnalisando(codigo, linguagem);
            if (deQuemChegouAntes.isPresent()) {
                log.info("Review {} aproveitou a análise de outro processo do mesmo código", review.getId());
                meterRegistry.counter("codereview.cache.stampede_evitado").increment();
                return convertCachedToAnalysis(deQuemChegouAntes.get());
            }
            log.info("Espera esgotada para o review {}, analisando por conta própria", review.getId());
        }

        String prompt = promptBuilder.buildPrompt(codigo, review.getLanguage());
        return ollamaService.analyze(prompt);
    }

    /**
     * Reconsulta o cache algumas vezes enquanto o outro processo trabalha.
     *
     * <p>Visível para teste porque a espera é o comportamento em questão, e
     * exercitá-la pelo laço de verdade tornaria o teste lento sem provar mais
     * nada.
     */
    Optional<ReviewResponse> aguardarQuemEstaAnalisando(String codigo, String linguagem) {
        for (int tentativa = 0; tentativa < tentativasDeEspera; tentativa++) {
            try {
                Thread.sleep(intervaloDaEsperaMs);
            } catch (InterruptedException interrompida) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
            Optional<ReviewResponse> pronto = cacheService.getCachedResult(codigo, linguagem);
            if (pronto.isPresent()) {
                return pronto;
            }
        }
        return Optional.empty();
    }

    private ReviewResult buildResult(Review review, LlmAnalysisResult analysis) {
        try {
            return ReviewResult.builder()
                    .review(review)
                    .score(analysis.score())
                    .summary(analysis.summary())
                    .bugsJson(objectMapper.writeValueAsString(analysis.bugs()))
                    .codeSmellsJson(objectMapper.writeValueAsString(analysis.codeSmells()))
                    .solidViolationsJson(objectMapper.writeValueAsString(analysis.solidViolations()))
                    .refactoringSuggestionsJson(objectMapper.writeValueAsString(analysis.refactoringSuggestions()))
                    .positiveAspectsJson(objectMapper.writeValueAsString(analysis.positiveAspects()))
                    .build();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize LLM result", e);
        }
    }

    private ReviewResponse buildResponseFromSaved(Review saved, LlmAnalysisResult analysis) {
        return new ReviewResponse(
                saved.getId(),
                saved.getStatus(),
                saved.getLanguage(),
                analysis.score(),
                analysis.summary(),
                analysis.bugs(),
                analysis.codeSmells(),
                analysis.solidViolations(),
                analysis.refactoringSuggestions(),
                analysis.positiveAspects(),
                saved.getSubmittedAt(),
                saved.getResult() != null ? saved.getResult().getAnalyzedAt() : Instant.now(),
                null
        );
    }

    private LlmAnalysisResult convertCachedToAnalysis(ReviewResponse cached) {
        return new LlmAnalysisResult(
                cached.score(),
                cached.summary(),
                cached.bugs(),
                cached.codeSmells(),
                cached.solidViolations(),
                cached.refactoringSuggestions(),
                cached.positiveAspects()
        );
    }

    private void markFailed(Review review, String errorMessage) {
        review.setStatus(Review.ReviewStatus.FAILED);
        review.setErrorMessage(errorMessage);
        review.setCompletedAt(Instant.now());
        reviewRepository.save(review);
    }
}
