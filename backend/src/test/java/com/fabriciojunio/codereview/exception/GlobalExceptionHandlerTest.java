package com.fabriciojunio.codereview.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Teste do tradutor de exceção para resposta HTTP.
 *
 * Duas preocupações: o status certo, porque cliente e monitoramento
 * reagem a ele, e não vazar detalhe interno numa falha inesperada. Stack
 * trace e mensagem de exceção crua em resposta de erro é entrega de mapa
 * para quem está sondando o sistema.
 */
@DisplayName("GlobalExceptionHandler")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("erro de validação vira 400 listando os campos")
    void validacao_vira_400() {
        BindingResult resultado = new BeanPropertyBindingResult(new Object(), "requisicao");
        resultado.rejectValue(null, "codigo", "não pode ficar vazio");

        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        when(ex.getBindingResult()).thenReturn(resultado);

        ProblemDetail pd = handler.handleValidation(ex);

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(pd.getTitle()).isEqualTo("Validation Failed");
        assertThat(pd.getType().toString()).isEqualTo("urn:codereview:error:validation");
        assertThat(pd.getProperties()).containsKey("timestamp");
    }

    @Test
    @DisplayName("estouro de cota vira 429, e não 500")
    void cota_vira_429() {
        ProblemDetail pd = handler.handleRateLimit(
                new RateLimitExceededException("limite de 20 revisões por hora atingido"));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(pd.getTitle()).isEqualTo("Rate Limit Exceeded");
        assertThat(pd.getDetail()).contains("20 revisões");
    }

    @Test
    @DisplayName("LLM fora do ar vira 503, sinalizando problema temporário")
    void llm_fora_vira_503() {
        ProblemDetail pd = handler.handleOllamaUnavailable(
                new OllamaUnavailableException("connection refused"));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(pd.getTitle()).isEqualTo("LLM Service Unavailable");
    }

    @Test
    @DisplayName("argumento inválido vira 400 com a mensagem de negócio")
    void argumento_invalido_vira_400() {
        ProblemDetail pd = handler.handleIllegalArgument(
                new IllegalArgumentException("Email already registered"));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(pd.getDetail()).isEqualTo("Email already registered");
    }

    @Test
    @DisplayName("falha de autenticação vira 401 sem dizer o que errou")
    void autenticacao_vira_401_generico() {
        ProblemDetail pd = handler.handleAuth(
                new BadCredentialsException("senha do usuario maria@empresa.com nao confere"));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(pd.getDetail()).isEqualTo("Authentication required");
        assertThat(pd.getDetail()).doesNotContain("maria@empresa.com");
    }

    @Test
    @DisplayName("acesso negado vira 403")
    void acesso_negado_vira_403() {
        ProblemDetail pd = handler.handleAccessDenied(new AccessDeniedException("sem permissao"));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(pd.getDetail()).isEqualTo("Access denied");
    }

    @Test
    @DisplayName("falha inesperada vira 500 sem vazar detalhe interno")
    void erro_inesperado_nao_vaza_detalhe() {
        ProblemDetail pd = handler.handleGeneric(
                new RuntimeException("ORA-00942: table SENHAS does not exist at 10.0.0.7"));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(pd.getDetail()).isEqualTo("An unexpected error occurred");
        assertThat(pd.getDetail()).doesNotContain("ORA-00942");
        assertThat(pd.getDetail()).doesNotContain("10.0.0.7");
        assertThat(pd.getDetail()).doesNotContain("SENHAS");
    }

    @Test
    @DisplayName("toda resposta de erro tem tipo e horário, para rastrear no log")
    void toda_resposta_e_rastreavel() {
        ProblemDetail[] respostas = {
                handler.handleRateLimit(new RateLimitExceededException("x")),
                handler.handleOllamaUnavailable(new OllamaUnavailableException("x")),
                handler.handleIllegalArgument(new IllegalArgumentException("x")),
                handler.handleAuth(new BadCredentialsException("x")),
                handler.handleAccessDenied(new AccessDeniedException("x")),
                handler.handleGeneric(new RuntimeException("x")),
        };

        for (ProblemDetail pd : respostas) {
            assertThat(pd.getType().toString()).startsWith("urn:codereview:error:");
            assertThat(pd.getTitle()).isNotBlank();
            assertThat(pd.getProperties()).containsKey("timestamp");
        }
    }
}
