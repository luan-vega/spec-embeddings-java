package br.codesearch.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.codesearch.application.port.ChunkDescriber;
import br.codesearch.application.port.Embedder;
import br.codesearch.domain.CodeSearchException;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Provedor externo só nasce com permissão explícita e avisa o que sai da máquina. */
class RemoteGateTest {

    HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    String fake() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] b = (ex.getRequestURI().getPath().endsWith("embeddings")
                    ? "{\"data\":[{\"embedding\":[1,0]}]}"
                    : ex.getRequestURI().getPath().endsWith("batchEmbedContents") ? "{\"embeddings\":[{\"values\":[1,0]}]}"
                    : "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Does a thing.\"}]}}],\"content\":[{\"type\":\"text\",\"text\":\"Does a thing.\"}],\"choices\":[{\"message\":{\"content\":\"Does a thing.\"}}]}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    static CodeSearchProperties props(String embedder, String describer, boolean allowRemote, String base) {
        return new CodeSearchProperties("", embedder, "", "", "", "", 0, 0, allowRemote, describer, "", base, "", 0, base, base, "", 0, "", "", "");
    }

    static CodeSearchProperties llmProps(String llm, boolean allowRemote, String base) {
        return new CodeSearchProperties("", "ollama", "", "", "", "", 0, 0, allowRemote, "", "", base, "", 0, base, base, "", 0, llm, "", "");
    }

    @Test
    void askModelsDefaultToClaudeAndNeedPermission() throws Exception {
        assertThat(llmProps("", false, "").llm()).isEqualTo("claude");
        for (String name : new String[] {"claude", "openai", "gemini"}) {
            assertThatThrownBy(() -> new CodeSearchConfig().languageModel(llmProps(name, false, "http://127.0.0.1:1"), true, "k", "k", "k"))
                    .isInstanceOfSatisfying(CodeSearchException.class, e -> {
                        assertThat(e.code()).isEqualTo("llm_unavailable");
                        assertThat(e.getMessage()).contains("external").contains("--allow-remote");
                    });
        }
        assertThatThrownBy(() -> new CodeSearchConfig().languageModel(llmProps("cohere", true, ""), true, "", "", ""))
                .hasMessageContaining("available: claude, openai, gemini, ollama");

        String base = fake();
        PrintStream oldErr = System.err;
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            var fast = new CodeSearchConfig().languageModel(llmProps("claude", true, base), true, "", "sk-ant", "");
            var strong = new CodeSearchConfig().languageModel(llmProps("claude", true, base), false, "", "sk-ant", "");
            assertThat(fast.modelId()).isEqualTo("claude:claude-haiku-5-5");
            assertThat(strong.modelId()).isEqualTo("claude:claude-opus-5-5");
            assertThat(fast.complete("s", "u", 10)).isEqualTo("Does a thing.");
        } finally {
            System.setErr(oldErr);
        }
        String notice = err.toString(StandardCharsets.UTF_8);
        assertThat(notice).contains("llm (validação) 'claude' é EXTERNO; as perguntas saem")
                .contains("llm (resposta) 'claude' é EXTERNO; as perguntas e os trechos de código achados saem").doesNotContain("sk-ant");
    }

    @Test
    void externalEmbedderIsRefusedWithoutPermission() {
        assertThatThrownBy(() -> new CodeSearchConfig().embedder(props("openai", "", false, "http://127.0.0.1:1"), "sk-x", ""))
                .isInstanceOfSatisfying(CodeSearchException.class, e -> {
                    assertThat(e.code()).isEqualTo("embedder_unavailable");
                    assertThat(e.getMessage()).contains("external").contains("--allow-remote").contains("CODE_SEARCH_ALLOW_REMOTE=1");
                });
    }

    @Test
    void externalDescribersAreRefusedWithoutPermissionAndOnlyWhenUsed() {
        for (String name : new String[] {"openai", "claude", "gemini"}) {
            ChunkDescriber lazy = new CodeSearchConfig().chunkDescriber(props("ollama", name, false, "http://127.0.0.1:1"), "k", "k", "k");
            // criar o bean não consulta ninguém; o gate dispara quando a indexação realmente descreve
            assertThatThrownBy(lazy::describerId).isInstanceOfSatisfying(CodeSearchException.class, e -> {
                assertThat(e.code()).isEqualTo("describer_unavailable");
                assertThat(e.getMessage()).contains("external");
            });
        }
    }

    @Test
    void noDescriberMeansEmptyIdAndTextAndLocal() {
        ChunkDescriber none = new CodeSearchConfig().chunkDescriber(props("ollama", "", false, ""), "", "", "");
        assertThat(none.describerId()).isEmpty();
        assertThat(none.describe("A", "x")).isEmpty();
        assertThat(none.local()).isTrue();
    }

    @Test
    void unknownProvidersListWhatExists() {
        assertThatThrownBy(() -> new CodeSearchConfig().embedder(props("cohere", "", true, ""), "", ""))
                .hasMessageContaining("available: ollama, openai, gemini");
        ChunkDescriber lazy = new CodeSearchConfig().chunkDescriber(props("ollama", "cohere", true, ""), "", "", "");
        assertThatThrownBy(lazy::describerId).hasMessageContaining("available: ollama, openai, claude, gemini");
    }

    @Test
    void permittedExternalProvidersWorkAndAnnounceWhatLeavesTheMachine() throws Exception {
        String base = fake();
        PrintStream oldErr = System.err;
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            Embedder embedder = new CodeSearchConfig().embedder(props("openai", "", true, base), "sk-x", "");
            assertThat(embedder.local()).isFalse();
            ChunkDescriber claude = new CodeSearchConfig().chunkDescriber(props("ollama", "claude", true, base), "", "sk-ant", "");
            assertThat(claude.describerId()).hasSize(16);
            assertThat(claude.describe("A.b", "void b() {}")).isEqualTo("Does a thing.");
            assertThat(claude.local()).isFalse();
            Embedder gemini = new CodeSearchConfig().embedder(props("gemini", "", true, base), "", "gk");
            assertThat(gemini.local()).isFalse();
            assertThat(new CodeSearchConfig().chunkDescriber(props("ollama", "gemini", true, base), "", "", "gk")
                    .describe("A.b", "void b() {}")).isEqualTo("Does a thing.");
        } finally {
            System.setErr(oldErr);
        }
        String notice = err.toString(StandardCharsets.UTF_8);
        assertThat(notice).contains("embedder 'openai' é EXTERNO").contains("describer 'claude' é EXTERNO").contains("embedder 'gemini' é EXTERNO").contains("127.0.0.1");
        assertThat(notice).doesNotContain("sk-x").doesNotContain("sk-ant").doesNotContain("gk");
    }
}
