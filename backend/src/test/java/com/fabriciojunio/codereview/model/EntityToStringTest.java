package com.fabriciojunio.codereview.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Entidade impressa em log não pode carregar dado sensível. O toString mostra
 * só o id; o resto (hash da senha, código enviado para revisão) fica de fora.
 */
class EntityToStringTest {

    @Test
    @DisplayName("usuário impresso mostra o id e nunca o hash da senha")
    void usuarioNaoImprimeSenha() {
        UUID id = UUID.randomUUID();
        User usuario = User.builder().id(id).email("ana@exemplo.com").password("$2a$12$hashDeTeste").build();

        assertThat(usuario.toString()).contains(id.toString())
                .doesNotContain("$2a$12$hashDeTeste")
                .doesNotContain("ana@exemplo.com");
    }

    @Test
    @DisplayName("revisão impressa não carrega o código enviado")
    void revisaoNaoImprimeCodigo() {
        Review revisao = Review.builder().id(UUID.randomUUID()).sourceCode("String senha = \"segredo\";").build();

        assertThat(revisao.toString()).doesNotContain("segredo");
    }
}
