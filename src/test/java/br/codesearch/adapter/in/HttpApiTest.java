package br.codesearch.adapter.in;

import static org.assertj.core.api.Assertions.assertThat;

import br.codesearch.application.IndexService;
import br.codesearch.application.fakes.BagOfWordsEmbedder;
import br.codesearch.application.port.Embedder;
import br.codesearch.application.port.PolicyStore;
import br.codesearch.application.port.RepoCatalog;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HttpApiTest.FakeEmbedderConfig.class)
class HttpApiTest {

    static Path home;
    static Path repo;
    static final JsonMapper JSON = JsonMapper.builder().build();

    @TestConfiguration
    static class FakeEmbedderConfig {
        @Bean
        @Primary
        Embedder fakeEmbedder() {
            return new BagOfWordsEmbedder();
        }
    }

    @BeforeAll
    static void sandbox() throws Exception {
        home = Files.createTempDirectory("code-search-http");
        repo = TestApp.gitRepo(Files.createTempDirectory("code-search-repo"));
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("code-search-http-home");
        home = dir;
        registry.add("code-search.home", dir::toString);
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    RepoCatalog catalog;
    @Autowired
    PolicyStore policies;
    @Autowired
    org.springframework.beans.factory.ObjectProvider<IndexService> indexer;

    final HttpClient client = HttpClient.newHttpClient();

    HttpResponse<String> send(String method, String path, String contentType, String body, String host) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (contentType != null) {
            b.header("Content-Type", contentType);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** O HttpClient do JDK não deixa trocar o Host; um socket cru deixa. Devolve a linha de status e o corpo. */
    String raw(String host, String path) throws Exception {
        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", port)) {
            socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n")
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return new String(socket.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    HttpResponse<String> search(String body) throws Exception {
        return send("POST", "/v1/search", "application/json", body, null);
    }

    void indexSample() {
        if (catalog.list().isEmpty()) {
            policies.writeDefault();
            catalog.add("pagamento", repo, List.of("src/main/java"));
            indexer.getObject().index();
        }
    }

    @Test
    void healthAnswersEvenWithoutAnIndexAndReadyReportsWhy() throws Exception {
        HttpResponse<String> health = send("GET", "/health", null, null, null);
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(health.body()).get("status").asString()).isEqualTo("ok");
        assertThat(health.headers().firstValue("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("no-store"));
    }

    @Test
    void searchReturnsResultsAndReadyTurnsGreenOnceIndexed() throws Exception {
        indexSample();
        HttpResponse<String> ready = send("GET", "/ready", null, null, null);
        assertThat(ready.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(ready.body()).get("ready").asBoolean()).isTrue();

        HttpResponse<String> res = search("{\"query\":\"juros compostos parcela\",\"top_k\":3}");
        assertThat(res.statusCode()).isEqualTo(200);
        JsonNode json = JSON.readTree(res.body());
        assertThat(json.get("status").asString()).isEqualTo("complete");
        assertThat(json.get("results").get(0).get("symbol").asString()).contains("calcularJurosComposto");
        assertThat(json.get("request_id").asString()).hasSize(32);
        assertThat(res.headers().firstValue("Content-Type").orElse("")).contains("application/json");
        assertThat(res.body()).doesNotContain(repo.toString());
    }

    @Test
    void rejectsBadRequestsWithStableErrorCodes() throws Exception {
        indexSample();
        record Case(HttpResponse<String> response, int status, String code) {}
        List<Case> cases = List.of(
                new Case(send("POST", "/v1/search", "text/plain", "{\"query\":\"x\"}", null), 400, "invalid_request"),
                new Case(send("POST", "/v1/search", null, "{\"query\":\"x\"}", null), 400, "invalid_request"),
                new Case(search("não é json"), 400, "invalid_request"),
                new Case(search("{\"query\":\"x\",\"surpresa\":1}"), 400, "invalid_request"),
                new Case(search("{\"query\":\"x\",\"top_k\":0}"), 400, "invalid_request"),
                new Case(search("{\"query\":\"x\",\"mode\":\"fuzzy\"}"), 400, "invalid_request"),
                new Case(search("{\"query\":\"x\",\"pad\":\"" + "a".repeat(70_000) + "\"}"), 413, "payload_too_large"),
                new Case(send("GET", "/v1/search", null, null, null), 405, "method_not_allowed"),
                new Case(send("PUT", "/v1/search", "application/json", "{}", null), 405, "method_not_allowed"),
                new Case(send("GET", "/nada", null, null, null), 404, "not_found"),
                new Case(send("GET", "/nada", null, null, null), 404, "not_found"));
        for (Case c : cases) {
            assertThat(c.response().statusCode()).as(c.response().body()).isEqualTo(c.status());
            JsonNode error = JSON.readTree(c.response().body()).get("error");
            assertThat(error.get("code").asString()).isEqualTo(c.code());
            assertThat(error.get("message").asString()).doesNotContain(home.toString());
        }
        assertThat(raw("evil.example.com", "/health")).startsWith("HTTP/1.1 400").contains("invalid Host header");
        assertThat(raw("evil.example.com:8765", "/v1/search")).startsWith("HTTP/1.1 400");
        assertThat(raw("localhost:" + port, "/health")).startsWith("HTTP/1.1 200");
        assertThat(raw("[::1]:" + port, "/health")).startsWith("HTTP/1.1 200");
    }

    @Test
    void askValidatesTheBodyAndRefusesAnExternalModelWithoutPermission() throws Exception {
        HttpResponse<String> bad = send("POST", "/v1/ask", "application/json", "{\"query\":\"x\"}", null);
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(send("POST", "/v1/ask", "text/plain", "{\"question\":\"x\"}", null).statusCode()).isEqualTo(400);

        HttpResponse<String> gated = send("POST", "/v1/ask", "application/json", "{\"question\":\"onde fica o estorno?\"}", null);
        assertThat(gated.statusCode()).isEqualTo(503);
        JsonNode error = JSON.readTree(gated.body()).get("error");
        assertThat(error.get("code").asString()).isEqualTo("llm_unavailable");
        assertThat(error.get("message").asString()).contains("--allow-remote");
    }
}
