package com.kailas.polaris.controller;

import com.kailas.polaris.limiter.RateLimitDecision;
import com.kailas.polaris.limiter.RateLimiterManager;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/simulate")
public class SimulateController {

    private final RateLimiterManager rateLimiterManager;

    public SimulateController(RateLimiterManager rateLimiterManager) {
        this.rateLimiterManager = rateLimiterManager;
    }

    @PostMapping
    public ResponseEntity<?> simulate(
            @RequestHeader("X-API-KEY") String apiKey,
            @RequestParam(defaultValue = "100") int count
    ) throws InterruptedException {
        if (count < 1 || count > 5000) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "count must be between 1 and 5000"));
        }

        int concurrency = Math.min(count, 100);
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startGun = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(count);
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger blocked = new AtomicInteger();
        AtomicInteger unauthorized = new AtomicInteger();

        for (int i = 0; i < count; i++) {
            pool.submit(() -> {
                try {
                    startGun.await();
                    RateLimitDecision decision = rateLimiterManager.check(apiKey);
                    if (decision.allowed()) {
                        allowed.incrementAndGet();
                    } else {
                        blocked.incrementAndGet();
                    }
                } catch (IllegalArgumentException e) {
                    unauthorized.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        long start = System.currentTimeMillis();
        startGun.countDown();
        done.await(60, TimeUnit.SECONDS);
        pool.shutdown();
        long durationMs = System.currentTimeMillis() - start;

        return ResponseEntity.ok(Map.of(
                "total", count,
                "allowed", allowed.get(),
                "blocked", blocked.get(),
                "unauthorized", unauthorized.get(),
                "durationMs", durationMs
        ));
    }
}
