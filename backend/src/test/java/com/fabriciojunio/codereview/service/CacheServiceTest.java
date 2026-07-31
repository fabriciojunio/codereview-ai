package com.fabriciojunio.codereview.service;

import com.fabriciojunio.codereview.dto.ReviewResponse;
import com.fabriciojunio.codereview.model.Review.Language;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Teste do cache.
 *
 * O ponto mais importante não é o acerto de cache, é o comportamento
 * quando o Redis cai: cache é otimização, não dependência. Se o Redis
 * morrer, o pedido tem que seguir e chamar o LLM, não estourar na cara
 * do usuário.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CacheService")
class CacheServiceTest {

    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private ValueOperations<String, Object> operacoes;

    private CacheService cacheService;

    private static final String CODIGO = "public class A { }";
    private static final ReviewResponse RESPOSTA = ReviewResponse.pending(
            UUID.randomUUID(), Language.java, Instant.now());

    @BeforeEach
    void preparar() {
        when(redisTemplate.opsForValue()).thenReturn(operacoes);
        cacheService = new CacheService(redisTemplate);
    }

    @Test
    @DisplayName("devolve o resultado guardado quando o código é o mesmo")
    void acerto_de_cache() {
        when(operacoes.get(anyString())).thenReturn(RESPOSTA);

        Optional<ReviewResponse> achado = cacheService.getCachedResult(CODIGO, "java");

        assertThat(achado).containsSame(RESPOSTA);
    }

    @Test
    @DisplayName("erro de cache quando não há nada guardado")
    void erro_de_cache() {
        when(operacoes.get(anyString())).thenReturn(null);

        assertThat(cacheService.getCachedResult(CODIGO, "java")).isEmpty();
    }

    @Test
    @DisplayName("ignora lixo de tipo inesperado guardado na chave")
    void tipo_inesperado_no_cache() {
        when(operacoes.get(anyString())).thenReturn("uma string qualquer");

        assertThat(cacheService.getCachedResult(CODIGO, "java")).isEmpty();
    }

    @Test
    @DisplayName("Redis fora do ar na leitura não derruba o pedido")
    void redis_fora_do_ar_na_leitura() {
        when(operacoes.get(anyString())).thenThrow(new RuntimeException("connection refused"));

        assertThat(cacheService.getCachedResult(CODIGO, "java")).isEmpty();
    }

    @Test
    @DisplayName("Redis fora do ar na escrita não derruba o pedido")
    void redis_fora_do_ar_na_escrita() {
        doThrow(new RuntimeException("connection refused"))
                .when(operacoes).set(anyString(), any(), any(Duration.class));

        cacheService.cacheResult(CODIGO, "java", RESPOSTA);
        // sem exceção: o resultado só deixou de ser guardado
    }

    @Test
    @DisplayName("mesmo código e mesma linguagem caem na mesma chave")
    void chave_estavel() {
        cacheService.cacheResult(CODIGO, "java", RESPOSTA);
        cacheService.cacheResult(CODIGO, "java", RESPOSTA);

        ArgumentCaptor<String> chaves = ArgumentCaptor.forClass(String.class);
        verify(operacoes, times(2)).set(chaves.capture(), any(), any(Duration.class));

        assertThat(chaves.getAllValues().get(0)).isEqualTo(chaves.getAllValues().get(1));
        assertThat(chaves.getAllValues().get(0)).startsWith("review:hash:");
    }

    @Test
    @DisplayName("linguagem diferente não reaproveita o resultado do mesmo texto")
    void linguagem_separa_a_chave() {
        cacheService.cacheResult(CODIGO, "java", RESPOSTA);
        cacheService.cacheResult(CODIGO, "python", RESPOSTA);

        ArgumentCaptor<String> chaves = ArgumentCaptor.forClass(String.class);
        verify(operacoes, times(2)).set(chaves.capture(), any(), any(Duration.class));

        assertThat(chaves.getAllValues().get(0)).isNotEqualTo(chaves.getAllValues().get(1));
    }

    @Test
    @DisplayName("código diferente gera chave diferente")
    void codigo_diferente_chave_diferente() {
        cacheService.cacheResult(CODIGO, "java", RESPOSTA);
        cacheService.cacheResult("public class B { }", "java", RESPOSTA);

        ArgumentCaptor<String> chaves = ArgumentCaptor.forClass(String.class);
        verify(operacoes, times(2)).set(chaves.capture(), any(), any(Duration.class));

        assertThat(chaves.getAllValues().get(0)).isNotEqualTo(chaves.getAllValues().get(1));
    }

    @Test
    @DisplayName("a chave não carrega o código-fonte, só o hash")
    void chave_nao_vaza_codigo() {
        cacheService.cacheResult("senha = 'segredo-do-cliente'", "python", RESPOSTA);

        ArgumentCaptor<String> chave = ArgumentCaptor.forClass(String.class);
        verify(operacoes).set(chave.capture(), any(), any(Duration.class));

        assertThat(chave.getValue()).doesNotContain("segredo-do-cliente");
        assertThat(chave.getValue()).matches("review:hash:[0-9a-f]{64}");
    }

    @Test
    @DisplayName("guarda com TTL, para o cache não virar depósito eterno")
    void guarda_com_ttl() {
        cacheService.cacheResult(CODIGO, "java", RESPOSTA);

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(operacoes).set(anyString(), any(), ttl.capture());

        assertThat(ttl.getValue()).isPositive();
    }
}
