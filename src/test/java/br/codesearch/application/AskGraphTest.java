package br.codesearch.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.codesearch.adapter.out.sparse.JavaSparseEncoder;
import br.codesearch.application.AskService.Ask;
import br.codesearch.application.fakes.BagOfWordsEmbedder;
import br.codesearch.application.fakes.InMemoryCache;
import br.codesearch.application.fakes.InMemoryRegistry;
import br.codesearch.application.fakes.InMemorySources;
import br.codesearch.application.fakes.InMemoryVectorStore;
import br.codesearch.application.fakes.ScriptedLanguageModel;
import br.codesearch.domain.CodeSearchException;
import br.codesearch.domain.ExclusionPolicy;
import br.codesearch.domain.RepoEntry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** GraphRAG no ask: os vizinhos no grafo de código entram no contexto depois dos trechos achados pela busca. */
class AskGraphTest {

    static final String PEDIDO = """
            package br.pedido;

            import br.pagamento.Pagamento;

            public class Pedido {
                private final Pagamento pagamento;

                public double fecharParcela(double valor, int meses) {
                    return valor + pagamento.calcularJurosComposto(valor, meses);
                }
            }
            """;

    InMemoryVectorStore store = new InMemoryVectorStore();
    InMemoryRegistry registry = new InMemoryRegistry();
    InMemorySources sources = new InMemorySources();
    BagOfWordsEmbedder embedder = new BagOfWordsEmbedder();
    JavaSparseEncoder sparse = new JavaSparseEncoder();
    ExclusionPolicy policy = ExclusionPolicy.fromDict(ExclusionPolicy.DEFAULT_POLICY);
    ScriptedLanguageModel validator = new ScriptedLanguageModel("fake:fast", false);
    ScriptedLanguageModel answerer = new ScriptedLanguageModel("fake:strong", false);
    GraphContext graph = new GraphContext(store, 1, 4);

    @BeforeEach
    void index() {
        registry.entries.add(new RepoEntry("svc-pagamento", "/a"));
        registry.entries.add(new RepoEntry("svc-pedido", "/b"));
        sources.repo("svc-pagamento").put("src/main/java/br/Pagamento.java", ApplicationTest.PAGAMENTO);
        sources.repo("svc-pedido").put("src/main/java/br/Pedido.java", PEDIDO);
        new IndexService(store, registry, embedder, sparse, policy, new InMemoryCache(), sources, null).index();
    }

    AskService service(boolean graphByDefault) {
        return new AskService(new SearchService(store, registry, embedder, sparse), validator, answerer, policy, graph,
                graphByDefault);
    }

    void script() {
        validator.reply("STATUS: ok\nINTENT: flow\nIDENTIFIERS: fecharParcela\nSEARCH: fecharParcela");
        answerer.reply("`Pedido.fecharParcela` [1] soma os juros de `Pagamento.calcularJurosComposto` [2].");
    }

    @Test
    void addsTheCalleeFromAnotherRepoWithItsRelationAndTheRelationsBlock() {
        script();
        AskResponse r = service(true).ask(new Ask("como a parcela é fechada?", null, null, null, 1, null));

        assertThat(r.status()).isEqualTo("answered");
        assertThat(r.sources()).extracting(s -> s.chunk().symbol())
                .startsWith("Pedido.fecharParcela", "Pagamento.calcularJurosComposto");
        AskResponse.Source seed = r.sources().get(0);
        AskResponse.Source callee = r.sources().get(1);
        assertThat(seed.via()).isNull();
        assertThat(callee.via()).isEqualTo(new AskResponse.Via(1, "called_by"));
        assertThat(callee.chunk().repo()).isEqualTo("svc-pagamento");
        assertThat(callee.cited()).isTrue();
        assertThat(r.warnings()).isEmpty();
        assertThat(r.timingsMs()).containsKey("graph");
        assertThat(r.plan().queries()).last().asString().startsWith("graph: ");

        var call = answerer.calls.get(0);
        assertThat(call.system()).contains("code graph");
        assertThat(call.user()).contains("<snippet n=\"2\"").contains("via=\"called_by [1]\"")
                .contains("<relations>").contains("[1] calls [2]");
    }

    @Test
    void offByDefaultTheContextIsExactlyTheSearchResult() {
        script();
        AskResponse r = service(false).ask(new Ask("como a parcela é fechada?", null, null, null, 1, null));

        assertThat(r.sources()).hasSize(1);
        assertThat(r.timingsMs()).doesNotContainKey("graph");
        assertThat(answerer.calls.get(0).system()).isEqualTo(AskService.ANSWER_SYSTEM);
        assertThat(answerer.calls.get(0).user()).doesNotContain("<relations>").doesNotContain("via=");
    }

    @Test
    void perQuestionSwitchOverridesTheDefault() {
        script();
        Ask ask = new Ask("como a parcela é fechada?", null, null, null, 1, null);
        assertThat(service(false).ask(ask.withGraph(true)).sources()).hasSizeGreaterThan(1);
        script();
        assertThat(service(true).ask(ask.withGraph(false)).sources()).hasSize(1);
    }

    @Test
    void neighborsStayInsideTheRequestedRepos() {
        script();
        AskResponse r = service(true).ask(new Ask("como a parcela é fechada?", List.of("svc-pedido"), null, null, 1, null));
        assertThat(r.sources()).allMatch(s -> s.chunk().repo().equals("svc-pedido"));
    }

    @Test
    void streamingSendsTheGraphSourcesToo() {
        script();
        AskServiceTest.Recorder rec = new AskServiceTest.Recorder();
        AskResponse r = service(true).ask(new Ask("como a parcela é fechada?", null, null, null, 1, null), rec);
        assertThat(rec.sources).hasSameSizeAs(r.sources());
        assertThat(rec.sources.get(1).via()).isNotNull();
    }

    @Test
    void graphRequestedWithoutAGraphIsRejected() {
        AskService plain = new AskService(new SearchService(store, registry, embedder, sparse), validator, answerer, policy);
        assertThatThrownBy(() -> plain.ask(Ask.of("x").withGraph(true)))
                .isInstanceOfSatisfying(CodeSearchException.class, e -> assertThat(e.code()).isEqualTo("invalid_request"));
        assertThatThrownBy(() -> new GraphContext(store, 3, 4)).isInstanceOf(CodeSearchException.class);
    }

    @Test
    void graphIsBuiltOncePerGenerationAndEvaluatedInItsOwnMode() {
        GraphContext.Built first = graph.current();
        assertThat(graph.current().graph()).isSameAs(first.graph());
        sources.repo("svc-pedido").revision = "rev2";
        sources.repo("svc-pedido").put("src/main/java/br/Outro.java", "package br; public class Outro { void a() { } }");
        new IndexService(store, registry, embedder, sparse, policy, new InMemoryCache(), sources, null).index();
        assertThat(graph.current().graph()).isNotSameAs(first.graph());

        SearchService search = new SearchService(store, registry, embedder, sparse);
        Evaluation.Case flow = new Evaluation.Case("f1", "UC4", "fecharParcela", List.of(
                new Evaluation.Relevant("svc-pedido", "src/main/java/br/Pedido.java", "Pedido.fecharParcela"),
                new Evaluation.Relevant("svc-pagamento", "src/main/java/br/Pagamento.java", "Pagamento.calcularJurosComposto")));
        Evaluation.Report report = Evaluation.evaluate(search::search, List.of(flow), List.of("hybrid"), 1, null,
                resp -> graph.expand(resp.results(), resp.coverage().searched()).extras().stream()
                        .map(GraphContext.Extra::result).toList());

        Map<String, Number> hybrid = report.modes().get("hybrid").get("UC4");
        Map<String, Number> withGraph = report.modes().get(Evaluation.GRAPH_MODE).get("UC4");
        assertThat(hybrid).doesNotContainKey("context_recall");
        assertThat(hybrid.get("recall@1").doubleValue()).isEqualTo(0.5);
        assertThat(withGraph.get("recall@1").doubleValue()).isEqualTo(0.5);
        assertThat(withGraph.get("context_recall").doubleValue()).isEqualTo(1.0);
        assertThat(withGraph.get("context_size").doubleValue()).isGreaterThan(1.0);
    }
}
