package br.codesearch.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.codesearch.adapter.out.sparse.JavaSparseEncoder;
import br.codesearch.application.SearchService.Query;
import br.codesearch.application.fakes.BagOfWordsEmbedder;
import br.codesearch.application.fakes.InMemoryCache;
import br.codesearch.application.fakes.InMemoryRegistry;
import br.codesearch.application.fakes.InMemorySources;
import br.codesearch.application.fakes.InMemoryVectorStore;
import br.codesearch.application.port.ChunkDescriber;
import br.codesearch.domain.CodeSearchException;
import br.codesearch.domain.ExclusionPolicy;
import br.codesearch.domain.RepoEntry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApplicationTest {

    static final String PAGAMENTO = """
            package br.pagamento;

            public class Pagamento {
                /** Calcula juros compostos da parcela. */
                public double calcularJurosComposto(double valor, int meses) {
                    return valor * Math.pow(1.02, meses) - valor;
                }

                public void estornarPagamento(String contaId) {
                    System.out.println("estorno " + contaId);
                }
            }
            """;
    static final String CLIENTE = """
            package br.cliente;

            public class Cliente {
                public boolean validarDocumento(String documento) {
                    return documento != null && documento.length() == 11;
                }
            }
            """;

    InMemoryVectorStore store = new InMemoryVectorStore();
    InMemoryRegistry registry = new InMemoryRegistry();
    InMemorySources sources = new InMemorySources();
    InMemoryCache cache = new InMemoryCache();
    BagOfWordsEmbedder embedder = new BagOfWordsEmbedder();
    JavaSparseEncoder sparse = new JavaSparseEncoder();
    ExclusionPolicy policy = ExclusionPolicy.fromDict(ExclusionPolicy.DEFAULT_POLICY);

    IndexService indexer(ChunkDescriber describer) {
        return new IndexService(store, registry, embedder, sparse, policy, cache, sources, describer);
    }

    SearchService searcher() {
        return new SearchService(store, registry, embedder, sparse);
    }

    @BeforeEach
    void repos() {
        registry.entries.add(new RepoEntry("svc-a", "/a"));
        registry.entries.add(new RepoEntry("svc-b", "/b"));
        sources.repo("svc-a").put("src/main/java/br/Pagamento.java", PAGAMENTO);
        sources.repo("svc-b").put("src/main/java/br/Cliente.java", CLIENTE);
    }

    @Test
    void indexesAllReposAndSearchFindsTheMethod() {
        IndexReport report = indexer(null).index();
        assertThat(report.status()).isEqualTo("published");
        assertThat(report.repos()).extracting(r -> r.chunks).containsExactly(3, 2);

        for (String mode : List.of("hybrid", "semantic", "lexical")) {
            SearchResponse res = searcher().search(Query.of("calcularJurosComposto").withMode(mode));
            assertThat(res.status()).isEqualTo("complete");
            assertThat(res.results().get(0).chunk().symbol()).as(mode).isEqualTo("Pagamento.calcularJurosComposto");
            assertThat(res.results().get(0).chunk().repo()).isEqualTo("svc-a");
        }
    }

    @Test
    void unchangedRepoIsSkippedAndForceReusesTheCache() {
        IndexService indexer = indexer(null);
        indexer.index();
        IndexReport again = indexer.index();
        assertThat(again.status()).isEqualTo("unchanged");
        assertThat(again.repos()).allMatch(r -> r.unchanged);

        IndexReport forced = indexer.index(null, true);
        assertThat(forced.status()).isEqualTo("published");
        assertThat(forced.repos()).allMatch(r -> r.embeddingsNew == 0 && r.embeddingsCached == r.chunks);

        sources.repo("svc-a").revision = "rev2";
        IndexReport changed = indexer.index();
        assertThat(changed.status()).isEqualTo("published");
        assertThat(changed.repos().get(0).unchanged).isFalse();
        assertThat(changed.repos().get(1).unchanged).isTrue();
    }

    @Test
    void invalidPolicyIndexesNothing() {
        policy = ExclusionPolicy.denyAll("policy_invalid");
        IndexReport report = indexer(null).index();
        assertThat(report.status()).isEqualTo("skipped");
        assertThat(report.reason()).isEqualTo("policy_invalid");
    }

    @Test
    void chunksWithSecretsAreNeverIndexed() {
        sources.repo("svc-a").put("src/main/java/br/Segredo.java", """
                public class Segredo {
                    public String chave() {
                        String password = "supersecreta123";
                        return password;
                    }
                }
                """);
        IndexReport report = indexer(null).index(List.of("svc-a"), false);
        assertThat(report.repos().get(0).chunksSkippedSecret).isEqualTo(1);
        assertThat(searcher().search(Query.of("supersecreta123").withMode("lexical")).results()).isEmpty();
    }

    @Test
    void policyExcludedAndNonUtf8FilesAreCountedNotIndexed() {
        sources.repo("svc-a").put("src/main/java/br/.env.java", "class X {}");
        sources.repo("svc-a").files.put("src/main/java/br/Ruim.java", new byte[] {(byte) 0xff, (byte) 0xfe, 'a'});
        IndexReport report = indexer(null).index(List.of("svc-a"), false);
        var r = report.repos().get(0);
        assertThat(r.filesSkippedPolicy).isEqualTo(1);
        assertThat(r.filesSkippedOther).isEqualTo(1);
        assertThat(r.filesIndexed).isEqualTo(1);
    }

    @Test
    void disabledRepoIsRemovedOnFullIndexAndUnknownIsRejected() {
        indexer(null).index();
        registry.entries.set(1, new RepoEntry("svc-b", "/b", RepoEntry.DEFAULT_ROOTS, false, "HEAD"));
        IndexReport report = indexer(null).index();
        assertThat(report.status()).isEqualTo("published");
        assertThat(report.removed()).containsExactly("svc-b");
        assertThatThrownBy(() -> indexer(null).index(List.of("nope"), false))
                .isInstanceOfSatisfying(CodeSearchException.class, e -> assertThat(e.code()).isEqualTo("invalid_request"));
    }

    @Test
    void invalidEmbedderOutputPublishesNothing() {
        embedder.returnZeros = true;
        assertThatThrownBy(() -> indexer(null).index())
                .isInstanceOfSatisfying(CodeSearchException.class, e -> assertThat(e.code()).isEqualTo("embedder_invalid_output"));
        assertThat(store.openActive()).isEmpty();
    }

    @Test
    void descriptionsReachTheEmbedderAndChangeTheSchema() {
        List<String> calls = new ArrayList<>();
        ChunkDescriber describer = new ChunkDescriber() {
            @Override
            public String describerId() {
                return "d1";
            }

            @Override
            public String describe(String symbol, String code) {
                calls.add(symbol);
                return "Does " + symbol + " for the customer.";
            }
        };
        IndexService withDescriber = indexer(describer);
        withDescriber.index();
        assertThat(calls).hasSize(5);
        assertThat(embedder.documents).allMatch(t -> t.contains("summary: Does "));
        assertThat(store.openActive().orElseThrow().schema().describerId()).isEqualTo("d1");
        assertThat(searcher().search(Query.of("calcularJurosComposto").withMode("lexical")).results().get(0).chunk().text())
                .doesNotContain("summary:");

        withDescriber.index(null, true);
        assertThat(calls).as("cache hits are not described again").hasSize(5);

        IndexReport withoutDescriber = indexer(null).index();
        assertThat(withoutDescriber.status()).as("schema changed, so everything is reindexed").isEqualTo("published");
        assertThat(withoutDescriber.repos()).allMatch(r -> !r.unchanged);
    }

    @Test
    void searchValidatesInputAndReportsCoverage() {
        indexer(null).index();
        Map<String, Query> bad = new HashMap<>();
        bad.put("empty", Query.of("  "));
        bad.put("mode", Query.of("x").withMode("fuzzy"));
        bad.put("topk", Query.of("x").withTopK(0));
        bad.put("topk-max", Query.of("x").withTopK(51));
        bad.put("repos", Query.of("x").withRepos(List.of()));
        bad.put("long", Query.of("x".repeat(2001)));
        bad.forEach((name, q) -> assertThatThrownBy(() -> searcher().search(q)).as(name)
                .isInstanceOfSatisfying(CodeSearchException.class, e -> assertThat(e.code()).isEqualTo("invalid_request")));

        SearchResponse partial = searcher().search(Query.of("validarDocumento").withRepos(List.of("svc-b", "ghost")));
        assertThat(partial.status()).isEqualTo("partial");
        assertThat(partial.coverage().searched()).containsExactly("svc-b");
        assertThat(partial.coverage().unavailable()).extracting(u -> u.reason()).containsExactly("unknown_repo");

        assertThatThrownBy(() -> searcher().search(Query.of("x").withRepos(List.of("ghost"))))
                .isInstanceOfSatisfying(CodeSearchException.class, e -> assertThat(e.code()).isEqualTo("no_repos_available"));
    }

    @Test
    void searchRefusesAnIndexBuiltWithAnotherProfile() {
        indexer(null).index();
        BagOfWordsEmbedder other = new BagOfWordsEmbedder(32);
        SearchService service = new SearchService(store, registry, other, sparse);
        assertThatThrownBy(() -> service.search(Query.of("x")))
                .isInstanceOfSatisfying(CodeSearchException.class, e -> assertThat(e.code()).isEqualTo("profile_mismatch"));
    }

    @Test
    void filtersAndOverlapCollapseApply() {
        indexer(null).index();
        SearchResponse onlyB = searcher().search(new Query("documento", "hybrid", null, List.of("java"), "src/main/java/br", 10, "req1"));
        assertThat(onlyB.requestId()).isEqualTo("req1");
        assertThat(onlyB.results()).isNotEmpty();
        SearchResponse none = searcher().search(new Query("documento", "hybrid", null, List.of("kotlin"), null, 10, null));
        assertThat(none.results()).isEmpty();
        assertThatThrownBy(() -> searcher().search(new Query("x", "hybrid", null, null, "../etc", 10, null)))
                .isInstanceOfSatisfying(CodeSearchException.class, e -> assertThat(e.code()).isEqualTo("invalid_request"));
    }
}
