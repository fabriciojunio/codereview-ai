package com.fabriciojunio.codereview.service;

import com.fabriciojunio.codereview.config.RedisConfig;
import com.fabriciojunio.codereview.dto.ReviewResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class CacheService {

    private static final String CACHE_PREFIX = "review:hash:";

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
            redisTemplate.opsForValue().set(key, response, RedisConfig.REVIEW_CACHE_TTL);
            log.debug("Cached result for key: {}", key);
        } catch (Exception e) {
            log.warn("Redis write failed, result not cached: {}", e.getMessage());
        }
    }

    private String buildKey(String sourceCode, String language) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((language + ":" + sourceCode).getBytes());
            return CACHE_PREFIX + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available in JVM
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
