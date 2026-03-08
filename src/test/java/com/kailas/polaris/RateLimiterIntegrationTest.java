package com.kailas.polaris;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class RateLimiterIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @LocalServerPort
    private int port;

    private final RestTemplate restTemplate = new RestTemplate();
    private String adminToken;
    private HttpHeaders adminHeaders;

    @BeforeEach
    void setUp() {
        adminToken = login("admin", "Admin@123");
        adminHeaders = new HttpHeaders();
        adminHeaders.setBearerAuth(adminToken);
        // Reset to sliding window before each test so strategy state is clean
        restTemplate.postForEntity(
                url("/admin/strategy?strategy=SLIDING_WINDOW"),
                new HttpEntity<>(adminHeaders),
                String.class);
    }

    // -------------------------------------------------------------------------
    // Sliding Window
    // -------------------------------------------------------------------------

    @Test
    void freePlanSlidingWindowBlocksRequestsBeyondLimit() throws InterruptedException {
        String keyValue = createApiKey("FREE");

        // 105 concurrent requests against a FREE plan limit of 100
        int[] result = fireConcurrently(105, buildRequestHeaders(keyValue));

        Assertions.assertEquals(100, result[0], "Sliding window must allow exactly 100 requests for FREE plan");
        Assertions.assertEquals(5, result[1], "Sliding window must block the 5 requests that exceed the limit");
    }

    @Test
    void proPlanSlidingWindowAllowsHigherLimit() throws InterruptedException {
        String keyValue = createApiKey("PRO");

        // 200 requests is well within the PRO limit of 1000 – all must pass
        int[] result = fireConcurrently(200, buildRequestHeaders(keyValue));

        Assertions.assertEquals(200, result[0], "PRO plan must allow 200 concurrent requests (limit is 1000)");
        Assertions.assertEquals(0, result[1], "PRO plan must not block any of the 200 requests");
    }

    // -------------------------------------------------------------------------
    // Token Bucket
    // -------------------------------------------------------------------------

    @Test
    void freePlanTokenBucketBlocksRequestsBeyondCapacity() throws InterruptedException {
        switchStrategy("TOKEN_BUCKET");
        String keyValue = createApiKey("FREE");

        // All 105 requests fire simultaneously so they arrive before the bucket
        // can refill (refill rate = 100/60 ≈ 1.67 tokens/sec; 105 requests land
        // within the same epoch-second, so delta = 0 and no refill occurs)
        int[] result = fireConcurrently(105, buildRequestHeaders(keyValue));
        int allowed = result[0], blocked = result[1];

        Assertions.assertEquals(105, allowed + blocked, "All 105 requests must be accounted for");
        Assertions.assertTrue(blocked > 0, "Token bucket must block requests once the bucket is empty");
        Assertions.assertTrue(allowed >= 100, "Token bucket must allow at least 100 requests (full capacity)");
    }

    @Test
    void tokenBucketAllowsFullBurstUpToCapacity() throws InterruptedException {
        switchStrategy("TOKEN_BUCKET");
        String keyValue = createApiKey("FREE");

        // Sending exactly the capacity should never trigger a block
        int[] result = fireConcurrently(100, buildRequestHeaders(keyValue));

        Assertions.assertEquals(100, result[0], "Token bucket must allow a burst equal to full capacity");
        Assertions.assertEquals(0, result[1], "Token bucket must not block when burst equals capacity");
    }

    // -------------------------------------------------------------------------
    // Strategy switching
    // -------------------------------------------------------------------------

    @Test
    void strategySwitchAppliesImmediately() throws InterruptedException {
        // Exhaust a FREE key under sliding window
        String keyValue1 = createApiKey("FREE");
        fireConcurrently(100, buildRequestHeaders(keyValue1));

        // Switch to token bucket and use a fresh key – bucket starts at full capacity
        switchStrategy("TOKEN_BUCKET");
        String keyValue2 = createApiKey("FREE");

        int[] result = fireConcurrently(50, buildRequestHeaders(keyValue2));

        Assertions.assertEquals(50, result[0], "After switching to token bucket, new key starts at full capacity");
        Assertions.assertEquals(0, result[1], "50 requests must not be blocked when bucket holds 100 tokens");
    }

    // -------------------------------------------------------------------------
    // API key lifecycle
    // -------------------------------------------------------------------------

    @Test
    void missingApiKeyReturns401() {
        // Authenticated request (Bearer token present) but no X-API-KEY header
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);

        try {
            restTemplate.exchange(url("/api/protected/test"), HttpMethod.GET,
                    new HttpEntity<>(headers), String.class);
            Assertions.fail("Expected 401 when X-API-KEY header is absent");
        } catch (HttpClientErrorException ex) {
            Assertions.assertEquals(401, ex.getStatusCode().value());
        }
    }

    @Test
    void inactiveApiKeyReturnsForbidden() {
        ResponseEntity<Map> createResp = restTemplate.postForEntity(
                url("/api/keys?plan=FREE"),
                new HttpEntity<>(adminHeaders), Map.class);
        String keyId = (String) createResp.getBody().get("id");
        String keyValue = (String) createResp.getBody().get("keyValue");

        // Deactivate the key
        restTemplate.exchange(url("/api/keys/" + keyId), HttpMethod.DELETE,
                new HttpEntity<>(adminHeaders), Void.class);

        try {
            restTemplate.exchange(url("/api/protected/test"), HttpMethod.GET,
                    new HttpEntity<>(buildRequestHeaders(keyValue)), String.class);
            Assertions.fail("Expected 403 for an inactive API key");
        } catch (HttpClientErrorException ex) {
            Assertions.assertEquals(403, ex.getStatusCode().value());
        }
    }

    @Test
    void blockedResponseIncludesRetryAfterHeader() throws InterruptedException {
        String keyValue = createApiKey("FREE");

        // Exhaust the full limit
        fireConcurrently(100, buildRequestHeaders(keyValue));

        // The very next request must be blocked and carry a Retry-After header
        try {
            restTemplate.exchange(url("/api/protected/test"), HttpMethod.GET,
                    new HttpEntity<>(buildRequestHeaders(keyValue)), String.class);
            Assertions.fail("Expected 429 after limit is exhausted");
        } catch (HttpClientErrorException ex) {
            Assertions.assertEquals(429, ex.getStatusCode().value());
            Assertions.assertNotNull(
                    ex.getResponseHeaders().getFirst("Retry-After"),
                    "429 response must include a Retry-After header");
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private String login(String username, String password) {
        ResponseEntity<Map> resp = restTemplate.postForEntity(
                url("/auth/login"),
                Map.of("username", username, "password", password),
                Map.class);
        Assertions.assertEquals(200, resp.getStatusCode().value());
        return resp.getBody().get("token").toString();
    }

    private String createApiKey(String plan) {
        ResponseEntity<Map> resp = restTemplate.postForEntity(
                url("/api/keys?plan=" + plan),
                new HttpEntity<>(adminHeaders), Map.class);
        Assertions.assertEquals(201, resp.getStatusCode().value());
        return (String) resp.getBody().get("keyValue");
    }

    private HttpHeaders buildRequestHeaders(String apiKeyValue) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-KEY", apiKeyValue);
        headers.setBearerAuth(adminToken);
        return headers;
    }

    private void switchStrategy(String strategy) {
        restTemplate.postForEntity(
                url("/admin/strategy?strategy=" + strategy),
                new HttpEntity<>(adminHeaders), String.class);
    }

    /**
     * Fires {@code count} HTTP requests concurrently using a start-gun latch
     * so all threads are released simultaneously, ensuring requests reach Redis
     * faster than any token/window replenishment can occur.
     *
     * @return int[]{allowed, blocked}
     */
    private int[] fireConcurrently(int count, HttpHeaders headers) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(count, 50));
        CountDownLatch startGun = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(count);
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger blocked = new AtomicInteger();

        for (int i = 0; i < count; i++) {
            pool.submit(() -> {
                try {
                    startGun.await();
                    int status;
                    try {
                        ResponseEntity<String> resp = restTemplate.exchange(
                                url("/api/protected/test"), HttpMethod.GET,
                                new HttpEntity<>(headers), String.class);
                        status = resp.getStatusCode().value();
                    } catch (HttpClientErrorException ex) {
                        status = ex.getStatusCode().value();
                    }
                    if (status == 200) allowed.incrementAndGet();
                    else if (status == 429) blocked.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        startGun.countDown();                    // release all threads at once
        done.await(30, TimeUnit.SECONDS);
        pool.shutdown();
        return new int[]{allowed.get(), blocked.get()};
    }
}
