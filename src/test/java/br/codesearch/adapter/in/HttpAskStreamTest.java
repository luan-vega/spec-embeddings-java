package br.codesearch.adapter.in;

import static org.assertj.core.api.Assertions.assertThat;

import br.codesearch.application.IndexService;
import br.codesearch.application.fakes.BagOfWordsEmbedder;
import br.codesearch.application.port.Embedder;
import br.codesearch.application.port.PolicyStore;
import br.codesearch.application.port.RepoCatalog;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
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

/** POST /v1/ask/stream de ponta a ponta, com o LLM local (Ollama) simulado em loopback respondendo em NDJSON. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HttpAskStreamTest.FakeEmbedderConfig.class)
class HttpAskStreamTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static HttpServer ollama;
    static final List<JsonNode> chats = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class FakeEmbedderConfig {
        @Bean
        @Primary
        Embedder fakeEmbedder() {
            return new BagOfWordsEmbedder();
        }
    }

    /** Validação sem streaming; resposta em três linhas NDJSON, cortando palavras ao meio. */
    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws Exception {
        ollama = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ollama.createContext("/api/chat", ex -> {
            JsonNode body = JSON.readTree(ex.getRequestBody().readAllBytes());
            chats.add(body);
            String reply = body.get("stream").asBoolean()
                    ? "{\"message\":{\"content\":\"O cálculo fica em \"},\"done\":false}\n"
                            + "{\"message\":{\"content\":\"`calcularJurosComposto` \"},\"done\":false}\n"
                            + "{\"message\":{\"content\":\"[1].\"},\"done\":false}\n{\"message\":{\"content\":\"\"},\"done\":true}\n"
                    : "{\"message\":{\"content\":\"STATUS: ok\\nIDENTIFIERS: calcularJurosComposto\\nSEARCH: compound interest\"}}";
            byte[] b = reply.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        ollama.start();
        registry.add("code-search.home", () -> tempDir("code-search-stream-home").toString());
        registry.add("code-search.llm", () -> "ollama");
        registry.add("code-search.llm-model", () -> "fake-llm");
        registry.add("code-search.ollama-url", () -> "http://127.0.0.1:" + ollama.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        ollama.stop(0);
    }

    static Path tempDir(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    RepoCatalog catalog;
    @Autowired
    PolicyStore policies;
    @Autowired
    ObjectProvider<IndexService> indexer;

    record Event(String name, JsonNode data) {}

    HttpResponse<String> post(String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/ask/stream"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
    }

    static List<Event> events(String body) {
        List<Event> out = new ArrayList<>();
        for (String frame : body.split("\n\n")) {
            String name = null;
            String data = null;
            for (String line : frame.split("\n")) {
                if (line.startsWith("event: ")) {
                    name = line.substring(7);
                } else if (line.startsWith("data: ")) {
                    data = line.substring(6);
                }
            }
            if (name != null) {
                out.add(new Event(name, JSON.readTree(data)));
            }
        }
        return out;
    }

    @Test
    void streamsStagesSourcesDeltasAndTheFinalResult() throws Exception {
        policies.writeDefault();
        catalog.add("pagamento", TestApp.gitRepo(tempDir("code-search-stream-repo")), List.of("src/main/java"));
        indexer.getObject().index();

        HttpResponse<String> res = post("{\"question\":\"como é calculado o juros composto?\",\"top_k\":3}");

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        List<Event> events = events(res.body());
        assertThat(events.stream().map(Event::name).distinct().toList())
                .containsExactly("stage", "sources", "delta", "result");
        assertThat(events.stream().filter(e -> e.name().equals("stage")).map(e -> e.data().get("stage").asString()).toList())
                .containsExactly("validate", "search", "answer");
        StringBuilder text = new StringBuilder();
        events.stream().filter(e -> e.name().equals("delta")).forEach(e -> text.append(e.data().get("text").asString()));
        assertThat(text.toString()).isEqualTo("O cálculo fica em `calcularJurosComposto` [1].");

        JsonNode sources = events.stream().filter(e -> e.name().equals("sources")).findFirst().orElseThrow().data();
        assertThat(sources.get(0).get("n").asInt()).isEqualTo(1);
        assertThat(sources.get(0).get("symbol").asString()).contains("calcularJurosComposto");
        JsonNode result = events.get(events.size() - 1).data();
        assertThat(result.get("status").asString()).isEqualTo("answered");
        assertThat(result.get("answer").asString()).isEqualTo(text.toString());
        assertThat(result.get("sources").get(0).get("cited").asBoolean()).isTrue();
        assertThat(chats).hasSize(2);
        assertThat(chats.get(0).get("stream").asBoolean()).isFalse();
        assertThat(chats.get(1).get("stream").asBoolean()).isTrue();
    }

    @Test
    void errorsBeforeTheFirstEventAreOrdinaryJson() throws Exception {
        HttpResponse<String> bad = post("{\"query\":\"x\"}");
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(bad.headers().firstValue("Content-Type").orElse("")).contains("application/json");
        assertThat(JSON.readTree(bad.body()).get("error").get("code").asString()).isEqualTo("invalid_request");
    }
}
