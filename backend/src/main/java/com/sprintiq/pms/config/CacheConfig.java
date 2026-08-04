package com.sprintiq.pms.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.Map;

@Configuration
public class CacheConfig {

    public static final String CACHE_AI_PREDICTIONS = "aiPredictions";
    public static final String CACHE_PERMISSIONS = "rolePermissions";
    public static final String CACHE_FEATURE_FLAGS = "featureFlags";
    public static final String CACHE_PROJECT_SUMMARY = "projectSummary";
    public static final String CACHE_VELOCITY_HISTORY = "velocityHistory";

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY)
                .activateDefaultTyping(new ObjectMapper().getPolymorphicTypeValidator(),
                        ObjectMapper.DefaultTyping.NON_FINAL);

        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer(mapper)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(base)
                .withInitialCacheConfigurations(Map.of(
                        CACHE_AI_PREDICTIONS, base.entryTtl(Duration.ofHours(6)),
                        CACHE_PERMISSIONS, base.entryTtl(Duration.ofMinutes(30)),
                        CACHE_FEATURE_FLAGS, base.entryTtl(Duration.ofMinutes(15)),
                        CACHE_PROJECT_SUMMARY, base.entryTtl(Duration.ofMinutes(5)),
                        CACHE_VELOCITY_HISTORY, base.entryTtl(Duration.ofMinutes(30))))
                .transactionAware()
                .build();
    }
}
