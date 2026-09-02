package com.fabriciojunio.codereview.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Teste do emissor de token.
 *
 * O que importa aqui não é o caminho feliz, é o caminho do atacante:
 * token adulterado, assinado com outra chave, expirado ou vazio precisam
 * ser recusados. Um validateToken que devolve true por engano abre o
 * sistema inteiro.
 */
@DisplayName("JwtProvider")
class JwtProviderTest {

    private static final String SEGREDO =
            Base64.getEncoder().encodeToString(
                    "chave-de-teste-com-pelo-menos-256-bits-de-tamanho!!".getBytes());

    private static final String OUTRO_SEGREDO =
            Base64.getEncoder().encodeToString(
                    "outra-chave-completamente-diferente-mas-do-tamanho".getBytes());

    private final JwtProvider provider = new JwtProvider(SEGREDO, 3_600_000L);

    @Test
    @DisplayName("gera token que ele mesmo valida e cujo dono é recuperável")
    void gera_e_le_o_proprio_token() {
        String token = provider.generateToken("maria@empresa.com");

        assertThat(token).isNotBlank();
        assertThat(provider.validateToken(token)).isTrue();
        assertThat(provider.extractEmail(token)).isEqualTo("maria@empresa.com");
    }

    @Test
    @DisplayName("recusa token assinado com outra chave")
    void recusa_assinatura_de_outra_chave() {
        SecretKey chaveDoAtacante = Keys.hmacShaKeyFor(Decoders.BASE64.decode(OUTRO_SEGREDO));
        String forjado = Jwts.builder()
                .subject("atacante@fora.com")
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(chaveDoAtacante)
                .compact();

        assertThat(provider.validateToken(forjado)).isFalse();
    }

    @Test
    @DisplayName("recusa token com o payload adulterado")
    void recusa_payload_adulterado() {
        String token = provider.generateToken("maria@empresa.com");
        String[] partes = token.split("\\.");

        String payloadAdulterado = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"sub\":\"admin@empresa.com\"}".getBytes());
        String adulterado = partes[0] + "." + payloadAdulterado + "." + partes[2];

        assertThat(provider.validateToken(adulterado)).isFalse();
    }

    @Test
    @DisplayName("recusa token expirado")
    void recusa_token_expirado() {
        JwtProvider jaNasceuVencido = new JwtProvider(SEGREDO, -1_000L);
        String token = jaNasceuVencido.generateToken("maria@empresa.com");

        assertThat(jaNasceuVencido.validateToken(token)).isFalse();
    }

    @Test
    @DisplayName("recusa lixo, texto vazio e nulo sem explodir")
    void recusa_entrada_invalida() {
        assertThat(provider.validateToken("isso nao e um jwt")).isFalse();
        assertThat(provider.validateToken("")).isFalse();
        assertThat(provider.validateToken(null)).isFalse();
        assertThat(provider.validateToken("a.b.c")).isFalse();
    }

    @Test
    @DisplayName("extractEmail estoura em token inválido, em vez de devolver nulo")
    void extract_email_falha_alto() {
        assertThatThrownBy(() -> provider.extractEmail("token.invalido.aqui"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("dois tokens do mesmo dono continuam válidos")
    void tokens_do_mesmo_dono_sao_validos() {
        String primeiro = provider.generateToken("maria@empresa.com");
        String segundo = provider.generateToken("maria@empresa.com");

        assertThat(provider.validateToken(primeiro)).isTrue();
        assertThat(provider.validateToken(segundo)).isTrue();
        assertThat(provider.extractEmail(segundo)).isEqualTo("maria@empresa.com");
    }
}
