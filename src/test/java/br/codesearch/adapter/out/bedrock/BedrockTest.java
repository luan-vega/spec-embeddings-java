package br.codesearch.adapter.out.bedrock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.codesearch.domain.CodeSearchException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Adapters do Bedrock contra um servidor falso (a AWS nunca é chamada); credenciais de mentira só nas propriedades da JVM. */
class BedrockTest {

    HttpServer server;
    final List<String> paths = new ArrayList<>();
    final List<String> bodies = new ArrayList<>();
    int status = 200;
    String embeddingsJson = "{\"embedding\":[3,4]}";

    @BeforeEach
    void start() throws Exception {
        System.setProperty("aws.accessKeyId", "test");
        System.setProperty("aws.secretAccessKey", "test");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            paths.add(ex.getRequestURI().getRawPath());
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String path = ex.getRequestURI().getRawPath();
            String json = path.endsWith("/converse")
                    ? "{\"output\":{\"message\":{\"role\":\"assistant\",\"content\":[{\"text\":\"Does a thing.\"}]}},\"stopReason\":\"end_turn\"}"
                    : embeddingsJson;
            byte[] b = json.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        System.clearProperty("aws.accessKeyId");
        System.clearProperty("aws.secretAccessKey");
    }

    BedrockConnection connection(String errorCode) {
        return BedrockConnection.create("us-east-1", "http://127.0.0.1:" + server.getAddress().getPort(), errorCode);
    }

    @Test
    void chatUsesConverseAndReportsHost() {
        BedrockChat chat = new BedrockChat(connection("llm_unavailable"), "us.anthropic.claude-test-v1:0");
        assertThat(chat.complete("sys", "user", 50)).isEqualTo("Does a thing.");
        assertThat(chat.modelId()).isEqualTo("bedrock:us.anthropic.claude-test-v1:0");
        assertThat(chat.local()).isFalse();
        assertThat(chat.host()).isEqualTo("127.0.0.1");
        assertThat(paths.get(0)).endsWith("/converse");
        assertThat(bodies.get(0)).contains("\"maxTokens\":50").contains("sys").contains("user");
    }

    @Test
    void describerReturnsOneLine() {
        BedrockDescriber d = new BedrockDescriber(connection("describer_unavailable"), "some-model");
        assertThat(d.describe("Foo.bar", "int bar() { return 1; }")).isNotBlank();
        assertThat(d.local()).isFalse();
        assertThat(d.describerId()).isNotEqualTo(new BedrockDescriber(connection("describer_unavailable"), "other").describerId());
    }

    @Test
    void titanEmbedsOneTextPerCallAndNormalizes() {
        BedrockEmbedder e = new BedrockEmbedder(connection("embedder_unavailable"), "amazon.titan-embed-text-v2:0", 256);
        assertThat(e.profile().dim()).isEqualTo(2);
        assertThat(e.profile().digest()).isEqualTo("remote:bedrock:256");
        float[] v = e.embedQuery("oi");
        assertThat(v[0]).isEqualTo(0.6f);
        assertThat(v[1]).isEqualTo(0.8f);
        assertThat(e.embedDocuments(List.of("a", "b"))).hasSize(2);
        assertThat(bodies.get(bodies.size() - 1)).contains("\"dimensions\":256").contains("\"normalize\":true");
    }

    @Test
    void cohereBatchesAndSeparatesDocumentFromQuery() {
        embeddingsJson = "{\"embeddings\":[[3,4]]}";
        BedrockEmbedder e = new BedrockEmbedder(connection("embedder_unavailable"), "cohere.embed-multilingual-v3", 0);
        e.embedQuery("oi");
        assertThat(bodies.get(bodies.size() - 1)).contains("\"input_type\":\"search_query\"");
        e.embedDocuments(List.of("a"));
        assertThat(bodies.get(bodies.size() - 1)).contains("\"input_type\":\"search_document\"");
    }

    @Test
    void cohereV4AcceptsTypedEmbeddings() {
        embeddingsJson = "{\"embeddings\":{\"float\":[[3,4]]}}";
        BedrockEmbedder e = new BedrockEmbedder(connection("embedder_unavailable"), "us.cohere.embed-v4:0", 0);
        assertThat(e.embedQuery("oi")).hasSize(2);
    }

    @Test
    void unknownFamilyIsRejected() {
        assertThatThrownBy(() -> new BedrockEmbedder(connection("embedder_unavailable"), "meta.llama3", 0))
                .isInstanceOfSatisfying(CodeSearchException.class, e -> assertThat(e.code()).isEqualTo("embedder_unavailable"));
    }

    @Test
    void awsErrorsAreStableAndDoNotEchoTheBody() {
        status = 403;
        BedrockChat chat = new BedrockChat(connection("llm_unavailable"), "m");
        assertThatThrownBy(() -> chat.complete("s", "SEGREDO", 10))
                .isInstanceOfSatisfying(CodeSearchException.class, e -> {
                    assertThat(e.code()).isEqualTo("llm_unavailable");
                    assertThat(e.getMessage()).contains("access denied").doesNotContain("SEGREDO");
                });
    }

    @Test
    void chatRequiresAModel() {
        assertThatThrownBy(() -> new BedrockChat(connection("llm_unavailable"), " "))
                .isInstanceOfSatisfying(CodeSearchException.class, e -> assertThat(e.code()).isEqualTo("llm_unavailable"));
    }
}
