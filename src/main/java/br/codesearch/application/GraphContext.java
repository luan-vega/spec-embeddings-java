package br.codesearch.application;

import br.codesearch.application.port.IndexSnapshot;
import br.codesearch.application.port.VectorStore;
import br.codesearch.domain.ChunkView;
import br.codesearch.domain.CodeGraph;
import br.codesearch.domain.CodeSearchException;
import br.codesearch.domain.Hit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Parte "graph" do GraphRAG: a partir dos trechos achados pela busca, traz os vizinhos no grafo de código (quem chama, quem é
 * chamado, supertipos e implementações, tipos usados) e diz como os trechos do contexto se ligam. O grafo é montado dos
 * chunks da geração ativa na primeira vez que é pedido e fica em memória até a geração mudar; não precisa reindexar.
 */
public final class GraphContext {

    public static final int MAX_HOPS = 2;
    public static final int MAX_EXTRA = 20;

    /** Trecho trazido pelo grafo: {@code from} é o índice (base 0) da semente e {@code relation} o código da ligação. */
    public record Extra(SearchResponse.Result result, int from, String relation) {}

    /** Vizinhos trazidos e relações entre todos os trechos (sementes seguidas dos vizinhos, índices base 0). */
    public record Expansion(List<Extra> extras, List<CodeGraph.Link> links) {

        static final Expansion EMPTY = new Expansion(List.of(), List.of());
    }

    private final VectorStore store;
    private final int hops;
    private final int maxExtra;
    private String generation;
    private CodeGraph graph;

    public GraphContext(VectorStore store, int hops, int maxExtra) {
        if (hops < 1 || hops > MAX_HOPS || maxExtra < 0 || maxExtra > MAX_EXTRA) {
            throw new CodeSearchException("invalid_request", "graph hops must be 1-" + MAX_HOPS + " and max extra 0-" + MAX_EXTRA);
        }
        this.store = store;
        this.hops = hops;
        this.maxExtra = maxExtra;
    }

    public int hops() {
        return hops;
    }

    public int maxExtra() {
        return maxExtra;
    }

    /** Grafo da geração ativa (montado uma vez por geração), com o snapshot de onde veio. */
    synchronized Built current() {
        IndexSnapshot snapshot = store.openActive().orElse(null);
        if (snapshot == null) {
            return null;
        }
        if (graph == null || !snapshot.generationId().equals(generation)) {
            graph = build(snapshot);
            generation = snapshot.generationId();
        }
        return new Built(graph, snapshot);
    }

    record Built(CodeGraph graph, IndexSnapshot snapshot) {}

    static CodeGraph build(IndexSnapshot snapshot) {
        List<CodeGraph.Input> inputs = new ArrayList<>();
        for (String repo : snapshot.repos()) {
            List<ChunkView> chunks = snapshot.records(repo);
            for (int row = 0; row < chunks.size(); row++) {
                ChunkView c = chunks.get(row);
                inputs.add(new CodeGraph.Input(repo, row, c.source(), c.symbol(), c.parentSymbol(), c.startLine(),
                        c.endLine(), c.text()));
            }
        }
        return CodeGraph.build(inputs);
    }

    /**
     * Expande os trechos achados. {@code repos}: só entram vizinhos desses repositórios (os que a busca cobriu). Trechos que
     * não estão no grafo (índice trocado no meio da pergunta) só ficam sem vizinhos.
     */
    public Expansion expand(List<SearchResponse.Result> seeds, Collection<String> repos) {
        Built built = current();
        if (built == null || seeds.isEmpty()) {
            return Expansion.EMPTY;
        }
        CodeGraph g = built.graph();
        List<Integer> seedIds = new ArrayList<>();
        List<Integer> seedPosition = new ArrayList<>();
        for (int i = 0; i < seeds.size(); i++) {
            ChunkView c = seeds.get(i).chunk();
            OptionalInt id = g.find(c.repo(), c.source(), c.symbol(), c.startLine(), c.endLine());
            if (id.isPresent()) {
                seedIds.add(id.getAsInt());
                seedPosition.add(i);
            }
        }
        Set<String> allowed = new HashSet<>(repos);
        List<CodeGraph.Neighbor> neighbors = g.expand(seedIds, hops, maxExtra, n -> allowed.contains(n.repo()));
        List<Extra> extras = new ArrayList<>();
        for (CodeGraph.Neighbor n : neighbors) {
            ChunkView chunk = built.snapshot().record(new Hit(n.info().repo(), n.info().row(), n.score()));
            extras.add(new Extra(new SearchResponse.Result(chunk, n.score()), seedPosition.get(n.seed()), n.via()));
        }
        // Relações entre todos os trechos do contexto: sementes (na ordem dada) e depois os vizinhos.
        List<Integer> all = new ArrayList<>();
        List<Integer> position = new ArrayList<>();
        for (int i = 0; i < seedIds.size(); i++) {
            all.add(seedIds.get(i));
            position.add(seedPosition.get(i));
        }
        for (int i = 0; i < neighbors.size(); i++) {
            all.add(neighbors.get(i).node());
            position.add(seeds.size() + i);
        }
        List<CodeGraph.Link> links = new ArrayList<>();
        for (CodeGraph.Link l : g.links(all)) {
            links.add(new CodeGraph.Link(position.get(l.from()), position.get(l.to()), l.relation()));
        }
        return new Expansion(List.copyOf(extras), List.copyOf(links));
    }
}
