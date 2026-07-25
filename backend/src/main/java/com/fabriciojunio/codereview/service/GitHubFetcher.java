package com.fabriciojunio.codereview.service;

import com.fabriciojunio.codereview.model.Review.Language;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class GitHubFetcher {

    private static final Pattern GITHUB_URL_PATTERN = Pattern.compile(
            "https://github\\.com/([^/]+)/([^/]+)/blob/([^/]+)/(.+\\.(java|py|js))"
    );
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(30);

    private final WebClient webClient;

    public GitHubFetcher() {
        this.webClient = WebClient.builder()
                .baseUrl("https://raw.githubusercontent.com")
                .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024)) // 2MB max
                .build();
    }

    public record FetchResult(String sourceCode, Language language, String filename) {}

    public FetchResult fetchFile(String githubUrl) {
        Matcher matcher = GITHUB_URL_PATTERN.matcher(githubUrl);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid GitHub file URL: " + githubUrl);
        }

        String owner = matcher.group(1);
        String repo = matcher.group(2);
        String branch = matcher.group(3);
        String filePath = matcher.group(4);
        String extension = matcher.group(5);

        // Defesa em profundidade contra path traversal: mesmo com o host fixo em
        // raw.githubusercontent.com, segmentos ".." poderiam reposicionar o caminho.
        if (filePath.contains("..") || owner.contains("..") || repo.contains("..") || branch.contains("..")) {
            throw new IllegalArgumentException("Invalid GitHub file URL (path traversal): " + githubUrl);
        }

        Language language = switch (extension) {
            case "java" -> Language.java;
            case "py" -> Language.python;
            case "js" -> Language.javascript;
            default -> throw new IllegalArgumentException("Unsupported file type: " + extension);
        };

        String rawPath = String.format("/%s/%s/%s/%s", owner, repo, branch, filePath);
        log.info("Fetching GitHub raw content from: {}", rawPath);

        String content;
        try {
            content = webClient.get()
                    .uri(rawPath)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, response -> {
                        if (response.statusCode().value() == 404) {
                            return response.createException().map(e ->
                                    new IllegalArgumentException("File not found on GitHub: " + githubUrl));
                        }
                        return response.createException().map(e ->
                                new IllegalArgumentException("GitHub returned " + response.statusCode() + " for: " + githubUrl));
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, response ->
                            response.createException().map(e ->
                                    new IllegalStateException("GitHub server error: " + response.statusCode())))
                    .bodyToMono(String.class)
                    .timeout(FETCH_TIMEOUT)
                    .block();
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (WebClientResponseException e) {
            throw new IllegalArgumentException("Failed to fetch file from GitHub: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new IllegalStateException("Unexpected error fetching GitHub file (timeout or network issue): " + e.getMessage(), e);
        }

        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("GitHub file is empty: " + githubUrl);
        }

        String filename = filePath.contains("/")
                ? filePath.substring(filePath.lastIndexOf('/') + 1)
                : filePath;

        return new FetchResult(content, language, filename);
    }
}
