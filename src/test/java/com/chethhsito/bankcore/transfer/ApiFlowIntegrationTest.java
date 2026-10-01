package com.chethhsito.bankcore.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "BANKCORE_TEST_DB_URL", matches = ".+")
class ApiFlowIntegrationTest {
    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("BANKCORE_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("BANKCORE_TEST_DB_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("BANKCORE_TEST_DB_PASSWORD"));
    }

    @Value("${local.server.port}") int port;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void userCanTryTheCompleteFlowAndSwagger() throws Exception {
        String suffix = UUID.randomUUID().toString();
        JsonNode alice = post("/api/v1/auth/register", "{\"email\":\"alice-" + suffix
                + "@example.com\",\"password\":\"examplePassword123\"}", null, null, 201);
        JsonNode bob = post("/api/v1/auth/register", "{\"email\":\"bob-" + suffix
                + "@example.com\",\"password\":\"examplePassword123\"}", null, null, 201);
        String sourceId = alice.get("accountId").asText();
        String destinationId = bob.get("accountId").asText();
        JsonNode login = post("/api/v1/auth/login", "{\"email\":\"alice-" + suffix
                + "@example.com\",\"password\":\"examplePassword123\"}", null, null, 200);
        String token = login.get("accessToken").asText();

        assertEquals(401, get("/api/v1/accounts", null).statusCode());
        post("/api/v1/test-deposits", "{\"destinationAccountId\":\"" + sourceId
                + "\",\"amount\":\"100.00\"}", token, null, 201);

        String key = UUID.randomUUID().toString();
        String body = "{\"sourceAccountId\":\"" + sourceId + "\",\"destinationAccountId\":\""
                + destinationId + "\",\"amount\":\"60.00\",\"currency\":\"PEN\"}";
        JsonNode first = post("/api/v1/transfers", body, token, key, 201);
        JsonNode retry = post("/api/v1/transfers", body, token, key, 201);
        assertEquals(first.get("id").asText(), retry.get("id").asText());
        JsonNode conflict = post("/api/v1/transfers", body.replace("60.00", "61.00"), token, key, 409);
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.get("code").asText());

        JsonNode accounts = json.readTree(get("/api/v1/accounts", token).body());
        assertEquals("40.00", accounts.get(0).get("availableBalance").asText());
        assertEquals(first.get("id").asText(), json.readTree(
                get("/api/v1/transfers/" + first.get("id").asText(), token).body()).get("id").asText());

        HttpResponse<String> docs = get("/v3/api-docs", null);
        assertEquals(200, docs.statusCode());
        assertTrue(docs.body().contains("/api/v1/transfers"));
        assertTrue(docs.body().contains("/api/v1/test-deposits"));
        HttpResponse<String> swagger = get("/swagger-ui.html", null);
        assertTrue(swagger.statusCode() == 200 || swagger.statusCode() == 302);
    }

    private JsonNode post(String path, String body, String token, String key, int expectedStatus) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (key != null) request.header("Idempotency-Key", key);
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(expectedStatus, response.statusCode(), response.body());
        return json.readTree(response.body());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
