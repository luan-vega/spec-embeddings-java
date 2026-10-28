package br.codesearch.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.codesearch.adapter.out.file.FileEmbeddingCache;
import br.codesearch.adapter.out.file.FilePolicyStore;
import br.codesearch.adapter.out.file.FileRepoRegistry;
import br.codesearch.adapter.out.file.FileVectorStore;
import br.codesearch.adapter.out.git.GitSource;
import br.codesearch.adapter.out.sparse.JavaSparseEncoder;
import br.codesearch.application.IndexReport;
import br.codesearch.application.IndexService;
import br.codesearch.application.SearchResponse;
import br.codesearch.application.SearchService;
import br.codesearch.application.SearchService.Query;
import br.codesearch.application.fakes.BagOfWordsEmbedder;
import br.codesearch.application.port.IndexSnapshot;
import br.codesearch.domain.ChunkRecord;
import br.codesearch.domain.CodeSearchException;
import br.codesearch.domain.EmbeddingProfile;
import br.codesearch.domain.ExclusionPolicy;
import br.codesearch.domain.Filters;
import br.codesearch.domain.Hit;
import br.codesearch.domain.IndexSchema;
import br.codesearch.domain.SegmentRef;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileStoreEndToEndTest {

    @TempDir
    Path tmp;

    static final EmbeddingProfile PROFILE = new EmbeddingProfile("m", "sha256:x", 3, "", "", 0);
    static final IndexSchema SCHEMA = new IndexSchema("c1", "t1", "p1");

    static ChunkRecord chunk(String source, String language, String symbol, String text, Map<String, Integer> terms, int line) {
        return new ChunkRecord(UUID.randomUUID().toString(), source, language, symbol, "", line, line + 2, text, terms);
    }

    SegmentRef segment(FileVectorStore store, String repo, String revision, List<ChunkRecord> chunks, float[][] dense) {
        return store.writeSegment(repo, revision, "2026-10-20T00:00:00Z", PROFILE, SCHEMA, chunks, dense, List.of("src/main/java"));
    }

    @Test
    void storesSearchesAndFiltersSegments() {
        FileVectorStore store = new FileVectorStore(tmp.resolve("store"));
        assertThat(store.openActive()).isEmpty();
        List<ChunkRecord> chunks = List.of(
                chunk("src/main/java/a/Pay.java", "java", "Pay.juros", "double juros() { }", Map.of("juros", 2, "pay", 1), 1),
                chunk("src/main/java/a/Pay.java", "java", "Pay.estorno", "void estorno() { }", Map.of("estorno", 1), 5),
                chunk("src/main/java/b/Doc.java", "java", "Doc.valida", "boolean valida() { }", Map.of("valida", 1, "juros", 1), 1));
        float[][] dense = {{1, 0, 0}, {0, 2, 0}, {0, 0, 3}};
        store.publish(PROFILE, SCHEMA, List.of(segment(store, "svc", "r1", chunks, dense)), List.of());

        IndexSnapshot snap = store.openActive().orElseThrow();
        assertThat(snap.repos()).containsExactly("svc");
        assertThat(snap.segmentInfo("svc").orElseThrow().chunks()).isEqualTo(3);
        assertThat(snap.segmentInfo("svc").orElseThrow().roots()).containsExactly("src/main/java");

        var dense1 = snap.search(List.of("svc"), new float[] {0, 5, 0}, List.of(), Filters.NONE, "semantic", 2);
        assertThat(dense1.get("dense")).extracting(Hit::row).containsExactly(1, 0);
        assertThat(dense1.get("dense").get(0).score()).isEqualTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(dense1.get("lexical")).isEmpty();

        var lex = snap.search(List.of("svc"), null, List.of("juros"), Filters.NONE, "lexical", 10);
        assertThat(lex.get("lexical")).extracting(Hit::row).containsExactly(0, 2);

        var byPath = snap.search(List.of("svc"), null, List.of("juros"), Filters.make(null, "src/main/java/b"), "lexical", 10);
        assertThat(byPath.get("lexical")).extracting(Hit::row).containsExactly(2);
        var byLang = snap.search(List.of("svc"), null, List.of("juros"), Filters.make(List.of("kotlin"), null), "lexical", 10);
        assertThat(byLang.get("lexical")).isEmpty();
        assertThat(snap.search(List.of("outro"), new float[] {1, 0, 0}, List.of(), Filters.NONE, "semantic", 5).get("dense")).isEmpty();

        var record = snap.record(new Hit("svc", 1, 0));
        assertThat(record.symbol()).isEqualTo("Pay.estorno");
        assertThat(record.text()).isEqualTo("void estorno() { }");
        assertThat(record.repoRevision()).isEqualTo("r1");
        assertThat(snap.locate(new Hit("svc", 2, 0)).source()).isEqualTo("src/main/java/b/Doc.java");
        // leitura em sequência (grafo do GraphRAG) igual à leitura linha a linha
        var all = snap.records("svc");
        assertThat(all).hasSize(3);
        for (int row = 0; row < all.size(); row++) {
            assertThat(all.get(row)).isEqualTo(snap.record(new Hit("svc", row, 0)));
        }
        assertThat(snap.records("outro")).isEmpty();

        assertThatThrownBy(() -> snap.search(List.of("svc"), new float[] {1, 0}, List.of(), Filters.NONE, "semantic", 5))
                .isInstanceOfSatisfying(CodeSearchException.class, e -> assertThat(e.code()).isEqualTo("profile_mismatch"));
        assertThatThrownBy(() -> snap.search(List.of("svc"), new float[] {0, 0, 0}, List.of(), Filters.NONE, "semantic", 5))
                .isInstanceOfSatisfying(CodeSearchException.class, e -> assertThat(e.code()).isEqualTo("embedder_invalid_output"));
        assertThatThrownBy(() -> snap.search(List.of("svc"), null, List.of(), Filters.NONE, "fuzzy", 5))
                .hasMessageContaining("mode");
    }

    @Test
    void rejectsMalformedSegments() {
        FileVectorStore store = new FileVectorStore(tmp.resolve("store"));
        List<ChunkRecord> one = List.of(chunk("a/A.java", "java", "A", "x", Map.of("a", 1), 1));
        assertThatThrownBy(() -> segment(store, "svc", "r", one, new float[][] {{0, 0, 0}}))
                .isInstanceOfSatisfying(CodeSearchException.class, e -> assertThat(e.code()).isEqualTo("embedder_invalid_output"));
        assertThatThrownBy(() -> segment(store, "svc", "r", one, new float[][] {{1, 0}}))
                .hasMessageContaining("dense matrix");
        assertThatThrownBy(() -> segment(store, "bad id", "r", one, new float[][] {{1, 0, 0}})).hasMessageContaining("repo_id");
        ChunkRecord dup = one.get(0);
        assertThatThrownBy(() -> segment(store, "svc", "r", List.of(dup, dup), new float[][] {{1, 0, 0}, {1, 0, 0}}))
                .hasMessageContaining("duplicate");
        assertThatThrownBy(() -> segment(store, "svc", "r",
                List.of(new ChunkRecord("not-a-uuid", "a/A.java", "java", "A", "", 1, 2, "x", Map.of())), new float[][] {{1, 0, 0}}))
                .hasMessageContaining("UUID");
    }

    @Test
    void generationsCarryOverRollBackAndPrune() throws Exception {
        FileVectorStore store = new FileVectorStore(tmp.resolve("store"));
        ChunkRecord c1 = chunk("a/A.java", "java", "A", "alpha", Map.of("alpha", 1), 1);
        ChunkRecord c2 = chunk("b/B.java", "java", "B", "beta", Map.of("beta", 1), 1);
        String g1 = store.publish(PROFILE, SCHEMA, List.of(segment(store, "a", "r1", List.of(c1), new float[][] {{1, 0, 0}})), List.of());
        String g2 = store.publish(PROFILE, SCHEMA, List.of(segment(store, "b", "r1", List.of(c2), new float[][] {{0, 1, 0}})), List.of());
        assertThat(store.activeGenerationId()).contains(g2);
        assertThat(store.previousGenerationId()).contains(g1);
        assertThat(store.openActive().orElseThrow().repos()).containsExactly("a", "b");

        assertThat(store.rollback()).isEqualTo(g1);
        assertThat(store.openActive().orElseThrow().repos()).containsExactly("a");
        assertThat(store.previousGenerationId()).contains(g2);

        String g3 = store.publish(PROFILE, SCHEMA, List.of(), List.of("a"));
        assertThat(store.openActive().orElseThrow().repos()).isEmpty();
        Map<String, Integer> pruned = store.prune(3600);
        assertThat(pruned.get("generations")).isEqualTo(1); // g2 não é nem ativa nem anterior... a anterior agora é g1
        assertThat(store.activeGenerationId()).contains(g3);
        assertThat(store.previousGenerationId()).contains(g1);
        assertThat(Files.list(tmp.resolve("store/segments")).count()).as("só o segmento de A segue referenciado").isEqualTo(1);

        assertThatThrownBy(() -> new FileVectorStore(tmp.resolve("empty")).rollback()).hasMessageContaining("no previous");
    }

    @Test
    void refusesProfileMismatchAndDetectsTampering() throws Exception {
        FileVectorStore store = new FileVectorStore(tmp.resolve("store"));
        SegmentRef ref = segment(store, "a", "r1", List.of(chunk("a/A.java", "java", "A", "alpha", Map.of("alpha", 1), 1)), new float[][] {{1, 0, 0}});
        EmbeddingProfile other = new EmbeddingProfile("m2", "sha256:x", 3, "", "", 0);
        assertThatThrownBy(() -> store.publish(other, SCHEMA, List.of(ref), List.of()))
                .isInstanceOfSatisfying(CodeSearchException.class, e -> assertThat(e.code()).isEqualTo("profile_mismatch"));
        assertThat(store.openActive()).isEmpty();

        Path chunks = tmp.resolve("store/segments").resolve(ref.segmentId()).resolve("chunks.jsonl");
        byte[] original = Files.readAllBytes(chunks);
        byte[] tampered = original.clone();
        tampered[tampered.length - 3] ^= 1; // mesmo tamanho, conteúdo diferente
        Files.write(chunks, tampered);
        assertThatThrownBy(() -> store.publish(PROFILE, SCHEMA, List.of(ref), List.of()))
                .isInstanceOfSatisfying(CodeSearchException.class, e -> assertThat(e.code()).isEqualTo("vector_store_unavailable"));
        Files.write(chunks, original);
        store.publish(PROFILE, SCHEMA, List.of(ref), List.of());

        Files.write(chunks, new byte[] {1});
        assertThatThrownBy(() -> new FileVectorStore(tmp.resolve("store")).openActive())
                .isInstanceOfSatisfying(CodeSearchException.class, e -> assertThat(e.code()).isEqualTo("vector_store_unavailable"));
    }

    @Test
    void indexesAndSearchesRealGitRepoThroughFileAdapters() throws Exception {
        Path repo = tmp.resolve("repo");
        Path file = repo.resolve("src/main/java/br/Pagamento.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                package br;
                public class Pagamento {
                    /** Calcula juros compostos da parcela. */
                    public double calcularJurosComposto(double valor, int meses) { return valor * meses; }
                    public void estornarPagamento(String contaId) { System.out.println(contaId); }
                }
                """);
        AdaptersTest.git(repo, "init", "-q");
        AdaptersTest.git(repo, "add", "-A");
        AdaptersTest.git(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "c");

        Path home = tmp.resolve("home");
        FileRepoRegistry registry = new FileRepoRegistry(home.resolve("repositories.json"));
        registry.add("pagamento", repo, List.of());
        FilePolicyStore.writeDefault(home.resolve("policy.json"));
        BagOfWordsEmbedder embedder = new BagOfWordsEmbedder();
        FileVectorStore store = new FileVectorStore(home.resolve("index"));
        IndexService indexer = new IndexService(store, registry, embedder, new JavaSparseEncoder(),
                FilePolicyStore.load(home.resolve("policy.json")), new FileEmbeddingCache(home.resolve("cache"), embedder.profile()),
                entry -> new GitSource(Path.of(entry.path()), entry.ref()), null);

        IndexReport first = indexer.index();
        assertThat(first.status()).isEqualTo("published");
        assertThat(first.repos().get(0).embeddingsNew).isEqualTo(3); // classe + 2 métodos
        assertThat(indexer.index().status()).as("mesmo commit: nada a fazer").isEqualTo("unchanged");

        SearchService searcher = new SearchService(store, registry, embedder, new JavaSparseEncoder());
        SearchResponse res = searcher.search(Query.of("juros compostos parcela"));
        assertThat(res.results().get(0).chunk().symbol()).contains("calcularJurosComposto");
        assertThat(res.results().get(0).chunk().source()).isEqualTo("src/main/java/br/Pagamento.java");

        // reindexação forçada reaproveita todos os vetores do cache em disco
        IndexReport forced = indexer.index(null, true);
        assertThat(forced.repos().get(0).embeddingsNew).isZero();
        assertThat(forced.repos().get(0).embeddingsCached).isEqualTo(3);
    }
}
