package com.lynceus.bff.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lynceus.bff.model.dto.FraudDistributionResponse;
import com.lynceus.bff.model.dto.OverviewResponse;
import com.lynceus.bff.model.dto.RiskBreakdownResponse;
import com.lynceus.bff.service.DashboardService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Configures how {@link DashboardService}'s cached dashboard views are stored in Redis. Three
 * separate, type-bound {@link RedisTemplate} beans rather than one {@code RedisTemplate<String,
 * Object>} — {@code overview}, {@code fraud-distribution}, and {@code risk-breakdown} are three
 * different cached shapes (see {@code DashboardService}'s cache keys/TTLs), and each {@link
 * Jackson2JsonRedisSerializer} here is bound to exactly one of them, for the same reason {@code
 * transaction-service}'s {@code RedisConfig} binds its serializer to {@code TransactionDto}
 * specifically rather than using {@link
 * org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer}'s polymorphic
 * {@code @class}-embedding default: polymorphic default typing is a deserialization-gadget attack
 * surface this fraud-detection platform's cache has no reason to opt into. {@code
 * transactions/recent} and {@code timeline} are never cached (see {@code DashboardService}), so
 * there's no fourth/fifth template here.
 */
@Configuration
public class RedisConfig {

  @Bean
  public RedisTemplate<String, OverviewResponse> overviewRedisTemplate(
      RedisConnectionFactory connectionFactory) {
    return typedTemplate(connectionFactory, OverviewResponse.class);
  }

  @Bean
  public RedisTemplate<String, FraudDistributionResponse> fraudDistributionRedisTemplate(
      RedisConnectionFactory connectionFactory) {
    return typedTemplate(connectionFactory, FraudDistributionResponse.class);
  }

  @Bean
  public RedisTemplate<String, RiskBreakdownResponse> riskBreakdownRedisTemplate(
      RedisConnectionFactory connectionFactory) {
    return typedTemplate(connectionFactory, RiskBreakdownResponse.class);
  }

  private <T> RedisTemplate<String, T> typedTemplate(
      RedisConnectionFactory connectionFactory, Class<T> type) {
    RedisTemplate<String, T> template = new RedisTemplate<>();
    template.setConnectionFactory(connectionFactory);

    StringRedisSerializer keySerializer = new StringRedisSerializer();
    Jackson2JsonRedisSerializer<T> valueSerializer =
        new Jackson2JsonRedisSerializer<>(redisObjectMapper(), type);

    template.setKeySerializer(keySerializer);
    template.setValueSerializer(valueSerializer);
    template.setHashKeySerializer(keySerializer);
    template.setHashValueSerializer(valueSerializer);
    template.afterPropertiesSet();
    return template;
  }

  private ObjectMapper redisObjectMapper() {
    // TimelineResponse.Point/OverviewResponse carry java.time.Instant fields — the default
    // ObjectMapper Jackson2JsonRedisSerializer would otherwise build has no JavaTimeModule
    // registered and fails serialization outright.
    ObjectMapper objectMapper = new ObjectMapper();
    objectMapper.registerModule(new JavaTimeModule());
    objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    return objectMapper;
  }
}
