package com.kailas.polaris.limiter;

import com.kailas.polaris.model.ApiKey;
import com.kailas.polaris.model.Policy;
import com.kailas.polaris.policy.PolicyCache;
import com.kailas.polaris.repository.ApiKeyRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@SuppressWarnings({"rawtypes", "unchecked"})
@Component
public class SlidingWindowRateLimiter implements RateLimiter {

    private final DefaultRedisScript<List> slidingWindowScript;
    private final StringRedisTemplate redisTemplate;
    private final ApiKeyRepository apiKeyRepository;
    private final PolicyCache policyCache;

    public SlidingWindowRateLimiter(
            StringRedisTemplate redisTemplate,
            ApiKeyRepository apiKeyRepository,
            PolicyCache policyCache
    ) {
        this.redisTemplate = redisTemplate;
        this.apiKeyRepository = apiKeyRepository;
        this.policyCache = policyCache;
        this.slidingWindowScript = loadScript();
    }

    @Override
    public RateLimitDecision check(String apiKeyValue) {
        ApiKey apiKey = apiKeyRepository.findByKeyValue(apiKeyValue)
                .orElseThrow(() -> new IllegalArgumentException("Invalid API key"));
        if (!apiKey.isActive()) {
            throw new IllegalArgumentException("Inactive API key");
        }

        Policy policy = policyCache.getPolicy(apiKey.getPlanType());

        long now = System.currentTimeMillis() / 1000;
        long window = policy.getWindowSeconds();
        long limit = policy.getLimitCount();
        String redisKey = "rate_limit:sw:{" + apiKeyValue + "}";
        String member = now + "-" + UUID.randomUUID();

        List<Long> result;
        try {
            result = redisTemplate.execute(
                    slidingWindowScript,
                    Collections.singletonList(redisKey),
                    String.valueOf(now),
                    String.valueOf(window),
                    String.valueOf(limit),
                    member
            );
        } catch (Exception ex) {
            return new RateLimitDecision(true, 0);
        }

        Long allowed = result.get(0);
        Long retryAfter = result.get(1);
        if (allowed == null || retryAfter == null) {
            return new RateLimitDecision(true, 0);
        }

        if (allowed == 1) {
            return new RateLimitDecision(true, 0);
        }

        return new RateLimitDecision(false, retryAfter);
    }

    private static DefaultRedisScript<List> loadScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setResultType(List.class);
        ClassPathResource resource = new ClassPathResource("scripts/sliding_window_rate_limiter.lua");
        try (var stream = resource.getInputStream()) {
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            script.setScriptText(text);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load sliding window rate limiter script", e);
        }
        return script;
    }
}
