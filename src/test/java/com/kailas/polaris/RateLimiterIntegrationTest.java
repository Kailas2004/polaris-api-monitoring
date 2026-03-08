package com.kailas.polaris;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assertions;
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
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpClientErrorException;
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

    @Test
    void freePlanLimitsEnforced() {
        String token = login("admin", "Admin@123");
        HttpHeaders adminHeaders = new HttpHeaders();
        adminHeaders.setBearerAuth(token);

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/keys?plan=FREE",
                new HttpEntity<>(adminHeaders),
                Map.class);
        Assertions.assertEquals(201, response.getStatusCode().value());
        Assertions.assertNotNull(response.getBody());
        String keyValue = (String) response.getBody().get("keyValue");
        Assertions.assertNotNull(keyValue);

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-KEY", keyValue);
        headers.setBearerAuth(token);
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger blocked = new AtomicInteger();

        for (int i = 0; i < 105; i++) {
            int status;
            try {
                ResponseEntity<String> hit = restTemplate.exchange(
                        "http://localhost:" + port + "/api/protected/test",
                        HttpMethod.GET,
                        new HttpEntity<>(headers),
                        String.class);
                status = hit.getStatusCode().value();
            } catch (HttpClientErrorException ex) {
                status = ex.getStatusCode().value();
            }

            if (status == 200) {
                allowed.incrementAndGet();
            } else if (status == 429) {
                blocked.incrementAndGet();
            }
        }

        Assertions.assertEquals(100, allowed.get());
        Assertions.assertEquals(5, blocked.get());
    }

    private String login(String username, String password) {
        ResponseEntity<Map> loginResponse = restTemplate.postForEntity(
                "http://localhost:" + port + "/auth/login",
                Map.of("username", username, "password", password),
                Map.class
        );
        Assertions.assertEquals(200, loginResponse.getStatusCode().value());
        Assertions.assertNotNull(loginResponse.getBody());
        Object token = loginResponse.getBody().get("token");
        Assertions.assertNotNull(token);
        return token.toString();
    }
}
