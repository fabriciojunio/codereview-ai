package com.fabriciojunio.codereview.service;

import com.fabriciojunio.codereview.config.RedisConfig;
import com.fabriciojunio.codereview.dto.ReviewResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * As duas defesas do cache: avalanche e estouro.
 *
 * <p>São problemas diferentes que costumam ser confundidos. Avalanche é muita
 * chave vencendo ao mesmo tempo, e a defesa é espalhar o vencimento. Estouro é
 * muita requisição atrás da mesma chave ausente, e a defesa é deixar só uma
 * fazer o trabalho.
 *
 * <p>Nenhum dos dois aparece em teste manual: os dois só se manifestam sob
 * concorrência ou depois de horas, e é por isso que valem teste automatizado.
 */
@DisplayName("Cache sob avalanche e estouro")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CacheAvalancheTest {

    private static final String CODIGO = "public class A { }";
    private static final String LINGUAGEM = "java";

    @Mock
    private RedisTemplate<String, Object> redis;

    @Mock
    private ValueOperations<String, Object> valores;

    @InjectMocks
    private CacheService cache;

    @Nested
    @DisplayName("avalanche")
    class Avalanche {

        @Test
        @DisplayName("o prazo varia a cada gravação, senão o lote inteiro vence junto")
        void prazoVaria() {
            Set<Long> prazos = new HashSet<>();
            IntStream.range(0, 50).forEach(i -> prazos.add(cache.ttlComJitter().toSeconds()));

            assertThat(prazos)
                    .as("prazo fixo faz mil chaves gravadas hoje virarem mil ausências amanhã, no mesmo segundo")
                    .hasSizeGreaterThan(1);
        }

        @Test
        @DisplayName("o prazo fica dentro da faixa: nunca zero, nunca o dobro")
        void prazoDentroDaFaixa() {
            IntStream.range(0, 200).forEach(i -> {
                long prazo = cache.ttlComJitter().toSeconds();
                assertThat(prazo).isBetween(
                        CacheService.ttlMinimoEmSegundos(),
                        CacheService.ttlMaximoEmSegundos());
            });
        }

        @Test
        @DisplayName("a faixa fica em torno das 24 horas configuradas, e não em outro lugar")
        void faixaEmTornoDoConfigurado() {
            long configurado = RedisConfig.REVIEW_CACHE_TTL.toSeconds();

            assertThat(CacheService.ttlMinimoEmSegundos()).isLessThan(configurado);
            assertThat(CacheService.ttlMaximoEmSegundos()).isGreaterThan(configurado);
        }

        @Test
        @DisplayName("grava com o prazo variado, e não com o valor fixo da configuração")
        void gravaComPrazoVariado() {
            when(redis.opsForValue()).thenReturn(valores);

            cache.cacheResult(CODIGO, LINGUAGEM, ReviewResponse.pending(null, null, null));

            ArgumentCaptor<Duration> prazo = ArgumentCaptor.forClass(Duration.class);
            verify(valores).set(anyString(), any(), prazo.capture());
            assertThat(prazo.getValue().toSeconds()).isBetween(
                    CacheService.ttlMinimoEmSegundos(),
                    CacheService.ttlMaximoEmSegundos());
        }
    }

    @Nested
    @DisplayName("estouro")
    class Estouro {

        @Test
        @DisplayName("só o primeiro fica responsável pela análise")
        void soOPrimeiroReserva() {
            when(redis.opsForValue()).thenReturn(valores);
            when(valores.setIfAbsent(anyString(), any(), any(Duration.class)))
                    .thenReturn(true, false, false, false);

            assertThat(cache.reserveProcessing(CODIGO, LINGUAGEM)).isTrue();
            assertThat(cache.reserveProcessing(CODIGO, LINGUAGEM)).isFalse();
            assertThat(cache.reserveProcessing(CODIGO, LINGUAGEM)).isFalse();
            assertThat(cache.reserveProcessing(CODIGO, LINGUAGEM)).isFalse();
        }

        @Test
        @DisplayName("a reserva é testa-e-grava num passo, e não uma leitura seguida de escrita")
        void reservaEhAtomica() {
            when(redis.opsForValue()).thenReturn(valores);
            when(valores.setIfAbsent(anyString(), any(), any(Duration.class))).thenReturn(true);

            cache.reserveProcessing(CODIGO, LINGUAGEM);

            // Ler e depois gravar deixaria uma janela entre as duas chamadas, e
            // é exatamente nela que os outros nove entrariam.
            verify(valores).setIfAbsent(anyString(), any(), any(Duration.class));
            verify(valores, org.mockito.Mockito.never()).get(anyString());
        }

        @Test
        @DisplayName("a reserva tem prazo, senão um processo que morreu trava aquele código para sempre")
        void reservaTemPrazo() {
            when(redis.opsForValue()).thenReturn(valores);
            when(valores.setIfAbsent(anyString(), any(), any(Duration.class))).thenReturn(true);

            cache.reserveProcessing(CODIGO, LINGUAGEM);

            ArgumentCaptor<Duration> prazo = ArgumentCaptor.forClass(Duration.class);
            verify(valores).setIfAbsent(anyString(), any(), prazo.capture());
            assertThat(prazo.getValue()).isPositive();
            assertThat(prazo.getValue())
                    .as("reserva mais longa que o cache deixaria o código bloqueado por horas")
                    .isLessThan(RedisConfig.REVIEW_CACHE_TTL);
        }

        @Test
        @DisplayName("a reserva usa chave própria, e não a mesma do resultado")
        void reservaUsaChavePropria() {
            when(redis.opsForValue()).thenReturn(valores);
            when(valores.setIfAbsent(anyString(), any(), any(Duration.class))).thenReturn(true);

            cache.reserveProcessing(CODIGO, LINGUAGEM);

            ArgumentCaptor<String> chave = ArgumentCaptor.forClass(String.class);
            verify(valores).setIfAbsent(chave.capture(), any(), any(Duration.class));
            assertThat(chave.getValue())
                    .as("usar a mesma chave faria a marca de reserva ser lida como resultado")
                    .doesNotStartWith("review:hash:");
        }

        @Test
        @DisplayName("Redis fora do ar não impede a análise: perde-se a economia, não o resultado")
        void redisForaNaoBloqueia() {
            when(redis.opsForValue()).thenReturn(valores);
            when(valores.setIfAbsent(anyString(), any(), any(Duration.class)))
                    .thenThrow(new RedisConnectionFailureException("sem conexão"));

            assertThat(cache.reserveProcessing(CODIGO, LINGUAGEM))
                    .as("cache indisponível deixa o sistema lento, não quebrado")
                    .isTrue();
        }

        @Test
        @DisplayName("liberar a reserva apaga a marca")
        void liberarApagaAMarca() {
            cache.releaseProcessing(CODIGO, LINGUAGEM);

            ArgumentCaptor<String> chave = ArgumentCaptor.forClass(String.class);
            verify(redis).delete(chave.capture());
            assertThat(chave.getValue()).startsWith("review:processing:");
        }

        @Test
        @DisplayName("falha ao liberar não sobe: o resultado já está gravado e a reserva vence sozinha")
        void falhaAoLiberarNaoSobe() {
            when(redis.delete(anyString())).thenThrow(new RedisConnectionFailureException("sem conexão"));

            cache.releaseProcessing(CODIGO, LINGUAGEM);
        }
    }

    @Nested
    @DisplayName("invalidação")
    class Invalidacao {

        @Test
        @DisplayName("apaga o resultado guardado, para trocar de modelo sem esperar 24 horas")
        void apagaOResultado() {
            cache.invalidate(CODIGO, LINGUAGEM);

            ArgumentCaptor<String> chave = ArgumentCaptor.forClass(String.class);
            verify(redis).delete(chave.capture());
            assertThat(chave.getValue()).startsWith("review:hash:");
        }

        @Test
        @DisplayName("invalidar um código não mexe no de outra linguagem")
        void naoAfetaOutraLinguagem() {
            cache.invalidate(CODIGO, "python");
            cache.invalidate(CODIGO, "java");

            ArgumentCaptor<String> chaves = ArgumentCaptor.forClass(String.class);
            verify(redis, org.mockito.Mockito.times(2)).delete(chaves.capture());
            assertThat(chaves.getAllValues().get(0))
                    .as("a linguagem entra no hash, senão o mesmo texto em duas linguagens colide")
                    .isNotEqualTo(chaves.getAllValues().get(1));
        }

        @Test
        @DisplayName("falha do Redis ao invalidar não sobe para quem chamou")
        void falhaNaoSobe() {
            when(redis.delete(anyString())).thenThrow(new RedisConnectionFailureException("sem conexão"));

            cache.invalidate(CODIGO, LINGUAGEM);
        }
    }
}
