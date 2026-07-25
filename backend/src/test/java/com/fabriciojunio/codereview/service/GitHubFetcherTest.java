package com.fabriciojunio.codereview.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("GitHubFetcher (validação de URL)")
class GitHubFetcherTest {

    private final GitHubFetcher fetcher = new GitHubFetcher();

    @Test
    @DisplayName("deve rejeitar URL que não é do GitHub")
    void fetchFile_urlInvalida_lancaExcecao() {
        assertThatThrownBy(() -> fetcher.fetchFile("https://evil.example.com/a/b/blob/main/App.java"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid GitHub file URL");
    }

    @Test
    @DisplayName("deve rejeitar path traversal com segmentos ..")
    void fetchFile_pathTraversal_lancaExcecao() {
        assertThatThrownBy(() ->
                fetcher.fetchFile("https://github.com/owner/repo/blob/main/../../secret.java"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path traversal");
    }

    @Test
    @DisplayName("deve rejeitar extensão de arquivo não suportada")
    void fetchFile_extensaoNaoSuportada_lancaExcecao() {
        assertThatThrownBy(() ->
                fetcher.fetchFile("https://github.com/owner/repo/blob/main/config.yml"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
