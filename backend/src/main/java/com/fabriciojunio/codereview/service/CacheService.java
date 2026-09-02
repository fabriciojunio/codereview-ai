package com.fabriciojunio.codereview.service;

import com.fabriciojunio.codereview.config.RedisConfig;
import com.fabriciojunio.codereview.dto.ReviewResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Cache de resultado por conteúdo submetido.
 *
 * <p>A chave é o hash do código, e não o id da submissão, porque rodar o mesmo
 * arquivo de novo é o caso mais comum em desenvolvimento e o mais caro de
 * todos: inferência de LLM ocupa a GPU por dezenas de segundos.
 *
 * <p>Falha do Redis nunca derruba a análise. Cache é otimização, e um cache
 * fora do ar deve deixar o sistema lento, não quebrado.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CacheService {

    private static final String CACHE_PREFIX = "review:hash:";

    /** Marca de "alguém já está analisando isto". Ver {@link #reserveProcessing}. */
    private static final String PROCESSING_PREFIX = "review:processing:";

    /**
     * Quanto o prazo de validade varia para cima e para baixo.
     *
     * <p>Sem isto, as chaves gravadas no mesmo lote expiram no mesmo segundo.
     * Um time que sobe uma análise em massa hoje cria, sem saber, uma rajada de
     * ausências de cache exatamente 24 horas depois, e todas elas vão para a
     * GPU ao mesmo tempo. Espalhar o vencimento em uma faixa converte um pico
     * numa ladeira.
     */
    private static final double JITTER = 0.10;

    /**
     * Prazo da reserva de processamento.
     *
     * <p>Precisa ser maior que o pior caso de inferência, senão a reserva vence
     * enquanto o modelo ainda está gerando e outro consumidor começa o mesmo
     * trabalho. Precisa ser bem menor que o prazo do cache, senão um processo
     * que morreu no meio bloqueia aquele código por horas.
     */
    private static final Duration PROCESSING_LEASE = Duration.ofMinutes(5);

    private final RedisTemplate<String, Object> redisTemplate;

    public Optional<ReviewResponse> getCachedResult(String sourceCode, String language) {
        String key = buildKey(sourceCode, language);
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached instanceof ReviewResponse response) {
                log.debug("Cache hit for key: {}", key);
                return Optional.of(response);
            }
        } catch (Exception e) {
            log.warn("Redis read failed, proceeding without cache: {}", e.getMessage());
        }
        return Optional.empty();
    }

    public void cacheResult(String sourceCode, String language, ReviewResponse response) {
        String key = buildKey(sourceCode, language);
        try {
            redisTemplate.opsForValue().set(key, response, ttlComJitter());
            log.debug("Cached result for key: {}", key);
        } catch (Exception e) {
            log.warn("Redis write failed, result not cached: {}", e.getMessage());
        }
    }

    /**
     * Tenta ficar responsável por analisar este código.
     *
     * <p>Resolve o estouro de cache. Quando dez submissões do mesmo arquivo
     * caem na fila juntas, as dez encontram o cache vazio e as dez chamam o
     * modelo, gastando dez vezes a GPU para produzir a mesma resposta. Aqui só
     * a primeira consegue a reserva.
     *
     * <p>A operação é {@code SET NX}, que testa e grava num passo só, no
     * servidor. Ler e depois gravar deixaria uma janela entre as duas
     * chamadas, que é exatamente onde as outras nove entrariam.
     *
     * @return {@code true} se esta chamada ficou responsável pela análise
     */
    public boolean reserveProcessing(String sourceCode, String language) {
        try {
            Boolean reservou = redisTemplate.opsForValue()
                    .setIfAbsent(processingKey(sourceCode, language), "1", PROCESSING_LEASE);
            return Boolean.TRUE.equals(reservou);
        } catch (Exception e) {
            // Redis fora do ar: seguir e analisar. Perde-se a economia de GPU,
            // não a análise.
            log.warn("Redis unavailable for processing lease, proceeding: {}", e.getMessage());
            return true;
        }
    }

    /**
     * Devolve a reserva assim que o resultado está no cache.
     *
     * <p>Sem isto a reserva só sairia ao vencer o prazo, e quem estivesse
     * esperando ficaria parado à toa depois de o resultado já existir.
     */
    public void releaseProcessing(String sourceCode, String language) {
        try {
            redisTemplate.delete(processingKey(sourceCode, language));
        } catch (Exception e) {
            log.warn("Failed to release processing lease: {}", e.getMessage());
        }
    }

    /**
     * Descarta o resultado guardado para um código.
     *
     * <p>Serve para quando a resposta guardada deixa de valer sem o código
     * mudar: troca do modelo, mudança no texto do prompt, correção no
     * interpretador da resposta. Sem isto, a única saída seria esperar 24
     * horas ou limpar o Redis inteiro.
     */
    public void invalidate(String sourceCode, String language) {
        try {
            redisTemplate.delete(buildKey(sourceCode, language));
            log.debug("Invalidated cache for {}", buildKey(sourceCode, language));
        } catch (Exception e) {
            log.warn("Failed to invalidate cache: {}", e.getMessage());
        }
    }

    /**
     * O prazo do cache, espalhado numa faixa em torno do valor configurado.
     *
     * <p>Visível para teste porque a faixa é a única coisa que dá para afirmar
     * sobre um valor aleatório.
     */
    Duration ttlComJitter() {
        long base = RedisConfig.REVIEW_CACHE_TTL.toSeconds();
        long faixa = (long) (base * JITTER);
        long desvio = ThreadLocalRandom.current().nextLong(-faixa, faixa + 1);
        return Duration.ofSeconds(base + desvio);
    }

    static long ttlMinimoEmSegundos() {
        return (long) (RedisConfig.REVIEW_CACHE_TTL.toSeconds() * (1 - JITTER));
    }

    static long ttlMaximoEmSegundos() {
        return (long) (RedisConfig.REVIEW_CACHE_TTL.toSeconds() * (1 + JITTER));
    }

    private String processingKey(String sourceCode, String language) {
        return PROCESSING_PREFIX + hashDe(sourceCode, language);
    }

    private String buildKey(String sourceCode, String language) {
        return CACHE_PREFIX + hashDe(sourceCode, language);
    }

    private String hashDe(String sourceCode, String language) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((language + ":" + sourceCode).getBytes());
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available in JVM
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
