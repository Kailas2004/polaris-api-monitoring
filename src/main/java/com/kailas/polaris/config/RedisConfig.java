package com.kailas.polaris.config;

import io.lettuce.core.RedisURI;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.util.StringUtils;

@Configuration
public class RedisConfig {

    @Bean
    public LettuceConnectionFactory redisConnectionFactory(
            @Value("${spring.data.redis.url:}") String redisUrl,
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.password:}") String password,
            @Value("${spring.data.redis.username:}") String username
    ) {
        if (StringUtils.hasText(redisUrl)) {
            RedisURI uri = RedisURI.create(redisUrl);
            RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(uri.getHost(), uri.getPort());
            if (uri.getPassword() != null && uri.getPassword().length > 0) {
                config.setPassword(RedisPassword.of(uri.getPassword()));
            }
            if (StringUtils.hasText(uri.getUsername())) {
                config.setUsername(uri.getUsername());
            }
            return new LettuceConnectionFactory(config);
        }
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(host, port);
        if (StringUtils.hasText(password)) {
            config.setPassword(RedisPassword.of(password));
        }
        if (StringUtils.hasText(username)) {
            config.setUsername(username);
        }
        return new LettuceConnectionFactory(config);
    }

    @Bean
    public RedisTemplate<String, String> redisTemplate(LettuceConnectionFactory factory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        StringRedisSerializer serializer = new StringRedisSerializer();
        template.setKeySerializer(serializer);
        template.setValueSerializer(serializer);
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public RedisScript<List> slidingWindowScript() {
        return RedisScript.of("""
                -- KEYS[1] = redisKey
                -- ARGV[1] = now
                -- ARGV[2] = window
                -- ARGV[3] = limit
                -- ARGV[4] = member

                local key = KEYS[1]
                local now = tonumber(ARGV[1])
                local window = tonumber(ARGV[2])
                local limit = tonumber(ARGV[3])
                local member = ARGV[4]

                redis.call("ZREMRANGEBYSCORE", key, 0, now - window)

                local count = redis.call("ZCARD", key)

                if count >= limit then
                    local earliest = redis.call("ZRANGE", key, 0, 0, "WITHSCORES")
                    if earliest[2] ~= nil then
                        local retryAfter = (tonumber(earliest[2]) + window) - now
                        if retryAfter < 0 then
                            retryAfter = 0
                        end
                        return {0, retryAfter}
                    end
                    return {0, window}
                end

                redis.call("ZADD", key, now, member)
                redis.call("EXPIRE", key, window)

                return {1, 0}
                """, List.class);
    }
}
