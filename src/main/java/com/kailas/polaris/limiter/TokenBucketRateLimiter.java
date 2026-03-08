package com.kailas.polaris.limiter;

import com.kailas.polaris.model.ApiKey;
import com.kailas.polaris.model.Policy;
import com.kailas.polaris.policy.PolicyCache;
import com.kailas.polaris.repository.ApiKeyRepository;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@SuppressWarnings({"rawtypes", "unchecked"})
@Component
public class TokenBucketRateLimiter implements RateLimiter {

    private static final String LUA_SCRIPT = """
            local key = KEYS[1]

            local capacity = tonumber(ARGV[1])
            local refill_rate = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])

            local data = redis.call("HMGET", key, "tokens", "last_refill")

            local tokens = tonumber(data[1])
            local last_refill = tonumber(data[2])

            if tokens == nil then
                tokens = capacity
                last_refill = now
            end

            local delta = math.max(0, now - last_refill)
            local refill = delta * refill_rate
            tokens = math.min(capacity, tokens + refill)

            local allowed = 0
            local retry_after = 0

            if tokens >= 1 then
                tokens = tokens - 1
                allowed = 1
            else
                retry_after = math.ceil((1 - tokens) / refill_rate)
            end

            redis.call("HMSET", key,
                "tokens", tokens,
                "last_refill", now
            )

            redis.call("EXPIRE", key, math.ceil(capacity / refill_rate))

            return {allowed, retry_after}
            """;

    private final StringRedisTemplate redisTemplate;
    private final ApiKeyRepository apiKeyRepository;
    private final PolicyCache policyCache;
    private DefaultRedisScript<List> script;

    public TokenBucketRateLimiter(
            StringRedisTemplate redisTemplate,
            ApiKeyRepository apiKeyRepository,
            PolicyCache policyCache
    ) {
        this.redisTemplate = redisTemplate;
        this.apiKeyRepository = apiKeyRepository;
        this.policyCache = policyCache;
    }

    @PostConstruct
    private void initScript() {
        DefaultRedisScript<List> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptText(LUA_SCRIPT);
        redisScript.setResultType(List.class);
        this.script = redisScript;
    }

    @Override
    public RateLimitDecision check(String apiKeyValue) {
        ApiKey apiKey = apiKeyRepository.findByKeyValue(apiKeyValue)
                .orElseThrow(() -> new IllegalArgumentException("Invalid API key"));
        if (!apiKey.isActive()) {
            throw new IllegalArgumentException("Inactive API key");
        }

        Policy policy = policyCache.getPolicy(apiKey.getPlanType());
        int capacity = policy.getLimitCount();
        int windowSeconds = policy.getWindowSeconds();
        double refillRate = (double) capacity / windowSeconds;
        long now = Instant.now().getEpochSecond();

        String redisKey = "rate_limit:tb:{" + apiKeyValue + "}";

        List<Long> result = redisTemplate.execute(
                script,
                Collections.singletonList(redisKey),
                String.valueOf(capacity),
                String.valueOf(refillRate),
                String.valueOf(now)
        );

        if (result == null || result.size() < 2) {
            return new RateLimitDecision(true, 0);
        }

        Long allowed = result.get(0);
        Long retryAfter = result.get(1);

        if (allowed != null && allowed == 1L) {
            return new RateLimitDecision(true, 0);
        }

        return new RateLimitDecision(false, retryAfter != null ? retryAfter : 0);
    }
}
