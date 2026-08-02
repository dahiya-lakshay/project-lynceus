package com.lynceus.transaction.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lynceus.shared.dto.TransactionDto;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Configures how scored transaction results are cached in Redis (see {@code
 * TransactionService.findById}). Values are serialized as JSON (rather than the JDK's native
 * serialization) so cached entries stay human-readable in Redis and aren't tied to a particular
 * class's serialVersionUID across deploys.
 *
 * <p>The bean is typed {@code RedisTemplate<String, Object>} (matching what {@code
 * TransactionService} depends on), but the value serializer underneath is a {@link
 * Jackson2JsonRedisSerializer} bound to the one concrete type this cache ever actually holds,
 * {@link TransactionDto}, rather than {@link
 * org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer}'s polymorphic
 * {@code @class}-embedding approach. Two reasons: (1) polymorphic default typing is a well-known
 * deserialization-gadget attack surface — unacceptable to opt into by default on a fraud-detection
 * platform's cache when it isn't needed — and (2) since there's only ever one cached shape, a
 * type-bound serializer is both simpler and doesn't depend on type metadata having been written by
 * whatever produced the cached bytes.
 */
@Configuration
public class RedisConfig {

  @Bean
  public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
    RedisTemplate<String, Object> template = new RedisTemplate<>();
    template.setConnectionFactory(connectionFactory);

    StringRedisSerializer keySerializer = new StringRedisSerializer();
    Jackson2JsonRedisSerializer<TransactionDto> valueSerializer =
        new Jackson2JsonRedisSerializer<>(redisObjectMapper(), TransactionDto.class);

    template.setKeySerializer(keySerializer);
    template.setValueSerializer(valueSerializer);
    template.setHashKeySerializer(keySerializer);
    template.setHashValueSerializer(valueSerializer);
    template.afterPropertiesSet();
    return template;
  }

  private ObjectMapper redisObjectMapper() {
    // TransactionDto carries java.time.Instant fields (created_at/updated_at); the default
    // ObjectMapper Jackson2JsonRedisSerializer would otherwise build has no JavaTimeModule
    // registered and fails serialization outright.
    ObjectMapper objectMapper = new ObjectMapper();
    objectMapper.registerModule(new JavaTimeModule());
    objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    return objectMapper;
  }
}
