package com.kailas.polaris.config;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory factory) {
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
