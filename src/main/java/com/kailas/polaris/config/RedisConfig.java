package com.kailas.polaris.config;

import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import io.netty.resolver.DefaultAddressResolverGroup;
import java.net.URI;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.util.StringUtils;

@Configuration
public class RedisConfig {

    @Bean(destroyMethod = "shutdown")
    public ClientResources lettuceClientResources() {
        // Use JDK DNS resolver (respects java.net.preferIPv6Addresses=true)
        // which is needed for Railway's IPv6-only private network
        return DefaultClientResources.builder()
                .addressResolverGroup(DefaultAddressResolverGroup.INSTANCE)
                .build();
    }

    @Bean
    public LettuceConnectionFactory redisConnectionFactory(
            ClientResources lettuceClientResources,
            @Value("${spring.data.redis.url:}") String redisUrl,
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.password:}") String password,
            @Value("${spring.data.redis.username:}") String username
    ) {
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .clientResources(lettuceClientResources)
                .build();

        RedisStandaloneConfiguration config;
        if (StringUtils.hasText(redisUrl)) {
            URI parsed = URI.create(redisUrl);
            config = new RedisStandaloneConfiguration(parsed.getHost(), parsed.getPort());
            String userInfo = parsed.getUserInfo();
            if (StringUtils.hasText(userInfo)) {
                int colon = userInfo.indexOf(':');
                if (colon >= 0) {
                    String uriUser = userInfo.substring(0, colon);
                    String uriPass = userInfo.substring(colon + 1);
                    if (StringUtils.hasText(uriPass)) {
                        config.setPassword(RedisPassword.of(uriPass));
                    }
                    if (StringUtils.hasText(uriUser)) {
                        config.setUsername(uriUser);
                    }
                } else {
                    config.setPassword(RedisPassword.of(userInfo));
                }
            }
        } else {
            config = new RedisStandaloneConfiguration(host, port);
            if (StringUtils.hasText(password)) {
                config.setPassword(RedisPassword.of(password));
            }
            if (StringUtils.hasText(username)) {
                config.setUsername(username);
            }
        }
        return new LettuceConnectionFactory(config, clientConfig);
    }

    @Bean
    public RedisTemplate<String, String> redisTemplate(LettuceConnectionFactory factory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        StringRedisSerializer serializer = new StringRedisSerializer();
        template.setKeySerializer(serializer);
        template.setValueSerializer(serializer);
        return template;
    }

    @SuppressWarnings("rawtypes")
    @Bean
    public RedisScript<List> slidingWindowScript() {
        return RedisScript.of("""
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