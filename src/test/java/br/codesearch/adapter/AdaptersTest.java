package br.codesearch.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.codesearch.adapter.out.file.FileEmbeddingCache;
import br.codesearch.adapter.out.file.FilePolicyStore;
import br.codesearch.adapter.out.file.FileRepoRegistry;
import br.codesearch.adapter.out.git.GitSource;
import br.codesearch.adapter.out.ollama.OllamaClient;
import br.codesearch.adapter.out.ollama.OllamaEmbedder;
import br.codesearch.application.port.SourceReader;
import br.codesearch.domain.CodeSearchException;
import br.codesearch.domain.EmbeddingProfile;
import br.codesearch.domain.ExclusionPolicy;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class AdaptersTest {

    @TempDir
    Path tmp;

    HttpServer server;
    final List<String> embedBodies = new CopyOnWriteArrayList<>();
    String embedResponse = "{\"embeddings\":[[3,4,0]]}";
    int embedStatus = 200;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    String fakeOllama(String installedName) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tags", ex -> reply(ex, 200,
                "{\"models\":[{\"name\":\"" + installedName + "\",\"digest\":\"abc123\"}]}"));
        server.createContext("/api/show", ex -> reply(ex, 200, "{\"model_info\":{\"bert.context_length\":2048}}"));
        server.createContext("/api/embed", ex -> {
            embedBodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(ex, embedStatus, embedResponse);
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    @Test
    void ollamaClientRefusesNonLoopbackHosts() {
        for (String url : List.of("http://example.com:11434", "http://10.0.0.5:11434", "https://127.0.0.1:11434")) {
            assertThatThrownBy(() -> new OllamaClient(url, Duration.ofSeconds(1)))
                    .isInstanceOfSatisfying(CodeSearchException.class, e -> assertThat(e.code()).isEqualTo("embedder_unavailable"));
        }
        new OllamaClient("http://localhost:11434", Duration.ofSeconds(1));
        new OllamaClient("http://[::1]:11434", Duration.ofSeconds(1));
    }

    @Test
    void embedderProbesInstalledModelAndNormalizesWithoutTruncating() throws Exception {
        String url = fakeOllama("nomic-embed-text:latest");
        OllamaEmbedder embedder = new OllamaEmbedder("nomic-embed-text", new OllamaClient(url, Duration.ofSeconds(5)), "doc: ", "q: ");
        assertThat(embedder.profile().dim()).isEqualTo(3);
        assertThat(embedder.profile().digest()).isEqualTo("sha256:abc123");
        assertThat(embedder.profile().maxInputTokens()).isEqualTo(2048);

        embedBodies.clear();
        float[] v = embedder.embedDocuments(List.of("x")).get(0);
        assertThat(v).containsExactly(0.6f, 0.8f, 0.0f);
        assertThat(embedBodies.get(0)).contains("\"truncate\":false").contains("doc: x");
        embedder.embedQuery("y");
        assertThat(embedBodies.get(1)).contains("q: y");
    }

    @Test
    void embedderNeverPullsAndRejectsInvalidOutput() throws Exception {
        String url = fakeOllama("other-model:latest");
        assertThatThrownBy(() -> new OllamaEmbedder("nomic-embed-text", new OllamaClient(url, Duration.ofSeconds(5)), "", ""))
                .hasMessageContaining("not installed");
        assertThat(embedBodies).as("only the tags listing happened, no pull or embed").isEmpty();

        OllamaEmbedder ok = new OllamaEmbedder("other-model", new OllamaClient(url, Duration.ofSeconds(5)), "", "");
        embedResponse = "{\"embeddings\":[[0,0,0]]}";
        assertThatThrownBy(() -> ok.embedQuery("z"))
                .isInstanceOfSatisfying(CodeSearchException.class, e -> assertThat(e.code()).isEqualTo("embedder_invalid_output"));
        embedResponse = "{\"embeddings\":[[1,2]]}";
        assertThatThrownBy(() -> ok.embedQuery("z"))
                .isInstanceOfSatisfying(CodeSearchException.class, e -> assertThat(e.code()).isEqualTo("embedder_invalid_output"));
        embedStatus = 400;
        assertThatThrownBy(() -> ok.embedQuery("z"))
                .isInstanceOfSatisfying(CodeSearchException.class, e -> assertThat(e.code()).isEqualTo("embedder_invalid_output"));
    }

    @Test
    void embedderReportsUnreachableOllama() throws Exception {
        String url = fakeOllama("m:latest");
        server.stop(0);
        server = null;
        assertThatThrownBy(() -> new OllamaEmbedder("m", new OllamaClient(url, Duration.ofSeconds(2)), "", ""))
                .isInstanceOfSatisfying(CodeSearchException.class, e -> assertThat(e.code()).isEqualTo("embedder_unavailable"));
    }

    @Test
    void cacheRoundTripsAndIgnoresIncompleteShards() throws Exception {
        EmbeddingProfile profile = new EmbeddingProfile("m", "sha256:x", 3, "", "", 0);
        FileEmbeddingCache cache = new FileEmbeddingCache(tmp.resolve("cache"), profile);
        String k1 = "a".repeat(64);
        String k2 = "b".repeat(64);
        cache.add(Map.of(k1, new float[] {1, 0, 0}, k2, new float[] {0, 1, 0}));
        Map<String, float[]> found = cache.lookup(List.of(k1, k2, "c".repeat(64)));
        assertThat(found).containsOnlyKeys(k1, k2);
        assertThat(found.get(k2)).containsExactly(0, 1, 0);

        Files.write(tmp.resolve("cache").resolve(profile.profileId()).resolve("shard-broken.keys"), new byte[16]);
        assertThat(new FileEmbeddingCache(tmp.resolve("cache"), profile).lookup(List.of(k1))).containsOnlyKeys(k1);

        assertThatThrownBy(() -> cache.add(Map.of(k1, new float[] {0, 0, 0})))
                .isInstanceOfSatisfying(CodeSearchException.class, e -> assertThat(e.code()).isEqualTo("embedder_invalid_output"));
        EmbeddingProfile other = new EmbeddingProfile("m2", "sha256:x", 3, "", "", 0);
        assertThat(new FileEmbeddingCache(tmp.resolve("cache"), other).lookup(List.of(k1))).isEmpty();
    }

    @Test
    void policyStoreDeniesWhenMissingOrInvalidAndLoadsTheDefault() throws Exception {
        Path file = tmp.resolve("p/policy.json");
        assertThat(FilePolicyStore.load(file).valid()).isFalse();
        assertThat(FilePolicyStore.load(file).reason()).isEqualTo("policy_missing");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{not json");
        assertThat(FilePolicyStore.load(file).reason()).isEqualTo("policy_invalid");
        FilePolicyStore.writeDefault(file);
        ExclusionPolicy loaded = FilePolicyStore.load(file);
        assertThat(loaded.valid()).isTrue();
        assertThat(loaded.versionId()).isEqualTo(ExclusionPolicy.fromDict(ExclusionPolicy.DEFAULT_POLICY).versionId());
    }

    @Test
    void registryAddsListsTogglesAndRemoves() throws Exception {
        Path repo = tmp.resolve("repo");
        Files.createDirectories(repo.resolve(".git"));
        FileRepoRegistry registry = new FileRepoRegistry(tmp.resolve("home/repositories.json"));
        assertThat(registry.list()).isEmpty();
        registry.add("svc-a", repo, List.of("src\\main\\java/"));
        assertThat(registry.get("svc-a").roots()).containsExactly("src/main/java");
        assertThatThrownBy(() -> registry.add("svc-a", repo, List.of())).hasMessageContaining("already");
        assertThatThrownBy(() -> registry.add("bad id", repo, List.of())).hasMessageContaining("repo_id");
        assertThatThrownBy(() -> registry.add("x", tmp, List.of())).hasMessageContaining("Git repository");
        assertThatThrownBy(() -> registry.add("y", repo, List.of("../etc"))).hasMessageContaining("root");

        registry.setEnabled("svc-a", false);
        assertThat(registry.enabled()).isEmpty();
        assertThat(registry.list()).hasSize(1);
        assertThat(registry.remove("svc-a")).isTrue();
        assertThat(registry.remove("svc-a")).isFalse();
        assertThatThrownBy(() -> registry.setEnabled("nope", true)).hasMessageContaining("unknown");
    }

    @Test
    void gitSourceReadsTheCommittedTreeNotTheWorkingTree() throws Exception {
        Path repo = tmp.resolve("g");
        Path file = repo.resolve("src/main/java/A.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class A {}\n");
        Files.writeString(repo.resolve("README.md"), "x");
        git(repo, "init", "-q");
        git(repo, "add", "-A");
        git(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "c");
        Files.writeString(file, "class A { /* sujo */ }\n");

        GitSource source = new GitSource(repo, "HEAD");
        assertThat(source.revision()).hasSize(40);
        var blobs = source.listBlobs(List.of("src/main/java"));
        assertThat(blobs).extracting(b -> b.path()).containsExactly("src/main/java/A.java");
        List<String> contents = new ArrayList<>();
        for (SourceReader.Blob blob : source.readBlobs(List.of(blobs.get(0).sha()))) {
            contents.add(new String(blob.content(), StandardCharsets.UTF_8));
        }
        assertThat(contents).containsExactly("class A {}\n");

        assertThatThrownBy(() -> new GitSource(tmp, "HEAD").revision())
                .isInstanceOfSatisfying(CodeSearchException.class, e -> assertThat(e.code()).isEqualTo("repo_unavailable"));
    }

    public static void git(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", dir.toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertThat(p.waitFor()).as(String.join(" ", args)).isZero();
    }
}
