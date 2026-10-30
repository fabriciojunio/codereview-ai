package com.fabriciojunio.codereview.config;

import com.fabriciojunio.codereview.dto.ReviewResponse;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

/**
 * O cache liga aqui, junto do CacheManager que o atende, e não na classe
 * principal: lá ele valeria também nos testes de fatia, que não têm Redis.
 */
@Configuration
@EnableCaching
public class RedisConfig {

    public static final String REVIEW_CACHE = "reviews";
    public static final Duration REVIEW_CACHE_TTL = Duration.ofHours(24);

    /**
     * Mapeador só do cache, fora do contexto: como bean, ele tomava o lugar do
     * mapeador da aplicação. No Jackson 3 java.time já vem embutido e data sai em
     * ISO-8601 por padrão.
     */
    private static final JsonMapper REDIS_MAPPER = JsonMapper.builder().build();

    /**
     * Serializador de valores tipado em {@link ReviewResponse}, o único objeto
     * cacheado. Diferente de GenericJacksonJsonRedisSerializer, não usa default
     * typing (não grava metadados {@code @class}), eliminando o vetor de
     * desserialização insegura via gadgets caso o Redis seja adulterado.
     */
    private JacksonJsonRedisSerializer<ReviewResponse> reviewSerializer() {
        return new JacksonJsonRedisSerializer<>(REDIS_MAPPER, ReviewResponse.class);
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(reviewSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(reviewSerializer());
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory factory) {
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(REVIEW_CACHE_TTL)
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(reviewSerializer()));

        return RedisCacheManager.builder(factory)
                .cacheDefaults(config)
                .build();
    }
}
