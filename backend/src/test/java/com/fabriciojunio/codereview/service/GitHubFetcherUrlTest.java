package com.fabriciojunio.codereview.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validação da URL antes de qualquer chamada de rede.
 *
 * Este é o ponto do sistema que aceita um endereço vindo do usuário e sai
 * buscando conteúdo, ou seja, a porta natural para SSRF e path traversal.
 * Tudo aqui roda sem rede de propósito: os casos testados são justamente
 * os que precisam ser recusados antes de o WebClient sair do lugar. Se um
 * destes começar a exigir rede para falhar, a validação furou.
 */
@DisplayName("GitHubFetcher, validação de URL")
class GitHubFetcherUrlTest {

    private final GitHubFetcher fetcher = new GitHubFetcher();

    @ParameterizedTest(name = "recusa host de fora: {0}")
    @ValueSource(strings = {
            "https://evil.example.com/dono/repo/blob/main/App.java",
            "https://github.com.evil.com/dono/repo/blob/main/App.java",
            "http://github.com/dono/repo/blob/main/App.java",
            "https://raw.githubusercontent.com/dono/repo/main/App.java",
            "https://gitlab.com/dono/repo/blob/main/App.java",
    })
    @DisplayName("só aceita https://github.com")
    void recusa_host_de_fora(String url) {
        assertThatThrownBy(() -> fetcher.fetchFile(url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid GitHub file URL");
    }

    @ParameterizedTest(name = "recusa formato inválido: {0}")
    @ValueSource(strings = {
            "https://github.com/dono/repo",
            "https://github.com/dono/repo/tree/main/pasta",
            "https://github.com/dono/repo/blob/main/",
            "https://github.com/dono/repo/raw/main/App.java",
            "nem parece uma url",
            "",
    })
    @DisplayName("recusa URL que não aponta para um arquivo em blob")
    void recusa_formato_invalido(String url) {
        assertThatThrownBy(() -> fetcher.fetchFile(url))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "recusa travessia: {0}")
    @ValueSource(strings = {
            "https://github.com/dono/repo/blob/main/../../etc/senhas.java",
            "https://github.com/dono/repo/blob/main/pasta/../../../App.java",
            "https://github.com/dono/repo/blob/..%2F..%2Fmain/App.java",
    })
    @DisplayName("recusa path traversal em qualquer posição da URL")
    void recusa_path_traversal(String url) {
        assertThatThrownBy(() -> fetcher.fetchFile(url))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "recusa extensão: {0}")
    @ValueSource(strings = {
            "https://github.com/dono/repo/blob/main/config.yml",
            "https://github.com/dono/repo/blob/main/.env",
            "https://github.com/dono/repo/blob/main/id_rsa",
            "https://github.com/dono/repo/blob/main/App.class",
            "https://github.com/dono/repo/blob/main/dump.sql",
    })
    @DisplayName("só busca arquivo de linguagem suportada")
    void recusa_extensao_nao_suportada(String url) {
        assertThatThrownBy(() -> fetcher.fetchFile(url))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("URL nula não passa despercebida")
    void recusa_url_nula() {
        assertThatThrownBy(() -> fetcher.fetchFile(null))
                .isInstanceOf(Exception.class);
    }
}
