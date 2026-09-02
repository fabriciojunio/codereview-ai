package com.fabriciojunio.codereview.service;

import com.fabriciojunio.codereview.dto.ReviewRequest;
import com.fabriciojunio.codereview.dto.ReviewResponse;
import com.fabriciojunio.codereview.exception.RateLimitExceededException;
import com.fabriciojunio.codereview.exception.ReviewNotFoundException;
import com.fabriciojunio.codereview.messaging.ReviewProducer;
import com.fabriciojunio.codereview.model.Review;
import com.fabriciojunio.codereview.model.Review.Language;
import com.fabriciojunio.codereview.model.User;
import com.fabriciojunio.codereview.repository.ReviewRepository;
import com.fabriciojunio.codereview.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Testes do serviço de revisão sem subir contexto do Spring.
 *
 * O foco é nas regras que protegem o sistema e o cliente: cota por hora,
 * limite de linhas e, principalmente, dono do ticket. Alguém pedir o
 * resultado de outra pessoa tem que ser indistinguível de pedir um
 * ticket que não existe.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ReviewService")
class ReviewServiceIsolatedTest {

    @Mock private ReviewRepository reviewRepository;
    @Mock private UserRepository userRepository;
    @Mock private ReviewProducer reviewProducer;

    private ReviewService service;

    private static final String EMAIL = "maria@empresa.com";
    private static final UUID TICKET = UUID.randomUUID();

    private User maria;

    @BeforeEach
    void preparar() {
        service = new ReviewService(
                reviewRepository, userRepository, reviewProducer,
                new ObjectMapper(), new SimpleMeterRegistry(),
                20, 1, 500);

        maria = User.builder()
                .id(UUID.randomUUID()).email(EMAIL).name("Maria").password("hash")
                .reviewCountThisHour(0).reviewWindowStart(Instant.now())
                .build();

        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(maria));
    }

    private Review revisaoDe(User dono) {
        Review r = Review.builder()
                .user(dono).language(Language.java).sourceCode("class A {}")
                .status(Review.ReviewStatus.PENDING)
                .build();
        r.setId(TICKET);
        r.setSubmittedAt(Instant.now());
        return r;
    }

    private void devolveAoSalvar() {
        when(reviewRepository.save(any(Review.class))).thenAnswer(i -> {
            Review r = i.getArgument(0);
            if (r.getId() == null) r.setId(TICKET);
            if (r.getSubmittedAt() == null) r.setSubmittedAt(Instant.now());
            return r;
        });
    }

    @Nested
    @DisplayName("envio")
    class Envio {

        @Test
        @DisplayName("aceita o código, guarda e enfileira o trabalho")
        void envio_feliz() {
            devolveAoSalvar();

            ReviewResponse resposta = service.submit(
                    new ReviewRequest("class A {}", Language.java, "A.java"), EMAIL);

            assertThat(resposta.ticketId()).isEqualTo(TICKET);
            assertThat(resposta.status()).isEqualTo(Review.ReviewStatus.PENDING);
            verify(reviewProducer).send(TICKET);
        }

        @Test
        @DisplayName("recusa código acima do limite de linhas, sem enfileirar nada")
        void recusa_codigo_gigante() {
            String gigante = "linha\n".repeat(501);

            assertThatThrownBy(() -> service.submit(
                    new ReviewRequest(gigante, Language.java, "A.java"), EMAIL))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maximum of 500 lines");

            verify(reviewProducer, never()).send(any());
            verify(reviewRepository, never()).save(any());
        }

        @Test
        @DisplayName("estoura a cota da hora e recusa, sem enfileirar")
        void recusa_acima_da_cota() {
            maria.setReviewCountThisHour(20);

            assertThatThrownBy(() -> service.submit(
                    new ReviewRequest("class A {}", Language.java, "A.java"), EMAIL))
                    .isInstanceOf(RateLimitExceededException.class)
                    .hasMessageContaining("20 reviews per hour");

            verify(reviewProducer, never()).send(any());
        }

        @Test
        @DisplayName("janela vencida zera a contagem, e o pedido passa")
        void janela_vencida_libera() {
            maria.setReviewCountThisHour(20);
            maria.setReviewWindowStart(Instant.now().minusSeconds(7200));
            devolveAoSalvar();

            ReviewResponse resposta = service.submit(
                    new ReviewRequest("class A {}", Language.java, "A.java"), EMAIL);

            assertThat(resposta.ticketId()).isEqualTo(TICKET);
        }

        @Test
        @DisplayName("cada envio consome uma unidade da cota")
        void envio_consome_cota() {
            devolveAoSalvar();
            service.submit(new ReviewRequest("class A {}", Language.java, "A.java"), EMAIL);

            assertThat(maria.getReviewCountThisHour()).isEqualTo(1);
        }

        @Test
        @DisplayName("usuário desconhecido não consegue enviar")
        void usuario_desconhecido() {
            when(userRepository.findByEmail("ninguem@empresa.com")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.submit(
                    new ReviewRequest("class A {}", Language.java, "A.java"), "ninguem@empresa.com"))
                    .isInstanceOf(UsernameNotFoundException.class);
        }

        @Test
        @DisplayName("envio vindo do GitHub guarda a URL de origem")
        void envio_do_github() {
            devolveAoSalvar();

            ReviewResponse resposta = service.submitGitHub(
                    "class A {}", Language.java, "A.java",
                    "https://github.com/org/repo/blob/main/A.java", EMAIL);

            assertThat(resposta.ticketId()).isEqualTo(TICKET);
            verify(reviewProducer).send(TICKET);
        }
    }

    @Nested
    @DisplayName("consulta")
    class Consulta {

        @Test
        @DisplayName("dono recebe o resultado da própria revisão")
        void dono_ve_o_resultado() {
            when(reviewRepository.findByIdWithResult(TICKET))
                    .thenReturn(Optional.of(revisaoDe(maria)));

            ReviewResponse resposta = service.getResult(TICKET, EMAIL);

            assertThat(resposta.ticketId()).isEqualTo(TICKET);
            assertThat(resposta.language()).isEqualTo(Language.java);
        }

        @Test
        @DisplayName("ticket inexistente vira ReviewNotFoundException, que a API traduz em 404")
        void ticket_inexistente() {
            when(reviewRepository.findByIdWithResult(TICKET)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getResult(TICKET, EMAIL))
                    .isInstanceOf(ReviewNotFoundException.class);
        }

        @Test
        @DisplayName("revisão de outra pessoa responde igual a inexistente, sem vazar que existe")
        void revisao_de_outro_dono() {
            User joao = User.builder()
                    .id(UUID.randomUUID()).email("joao@empresa.com").name("João").password("h")
                    .build();
            when(reviewRepository.findByIdWithResult(TICKET))
                    .thenReturn(Optional.of(revisaoDe(joao)));

            assertThatThrownBy(() -> service.getResult(TICKET, EMAIL))
                    .isInstanceOf(ReviewNotFoundException.class);
        }

        @Test
        @DisplayName("código-fonte de outra pessoa também não vaza")
        void codigo_de_outro_dono() {
            User joao = User.builder()
                    .id(UUID.randomUUID()).email("joao@empresa.com").name("João").password("h")
                    .build();
            when(reviewRepository.findById(TICKET)).thenReturn(Optional.of(revisaoDe(joao)));

            assertThatThrownBy(() -> service.getSourceCode(TICKET, EMAIL))
                    .isInstanceOf(ReviewNotFoundException.class);
        }

        @Test
        @DisplayName("dono recupera o próprio código-fonte e a linguagem")
        void dono_recupera_fonte_e_linguagem() {
            when(reviewRepository.findById(TICKET)).thenReturn(Optional.of(revisaoDe(maria)));

            assertThat(service.getSourceCode(TICKET, EMAIL)).isEqualTo("class A {}");
            assertThat(service.getLanguage(TICKET, EMAIL)).isEqualTo(Language.java);
        }

        @Test
        @DisplayName("linguagem de ticket inexistente também é 404, não 400")
        void linguagem_de_ticket_inexistente() {
            when(reviewRepository.findById(TICKET)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getLanguage(TICKET, EMAIL))
                    .isInstanceOf(ReviewNotFoundException.class);
        }

        @Test
        @DisplayName("revisão sem resultado ainda responde, com status e sem nota")
        void revisao_ainda_sem_resultado() {
            when(reviewRepository.findByIdWithResult(TICKET))
                    .thenReturn(Optional.of(revisaoDe(maria)));

            ReviewResponse resposta = service.getResult(TICKET, EMAIL);

            assertThat(resposta.score()).isNull();
            assertThat(resposta.status()).isEqualTo(Review.ReviewStatus.PENDING);
        }
    }
}
