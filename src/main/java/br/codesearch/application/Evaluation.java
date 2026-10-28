package br.codesearch.application;

import br.codesearch.domain.CodeSearchException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Harness de avaliação (gate): Recall@k, MRR, nDCG@k e latência p50/p95 por caso de uso e modo, comparados com limiares
 * definidos antes da rodada. Mesmas métricas, nomes e regras da versão Python, para rodar o mesmo dataset nas duas.
 * Não lê arquivos nem JSON: o dataset e os limiares chegam já carregados.
 */
public final class Evaluation {

    public static final List<String> MODES = SearchService.MODES;
    /** Modo extra do GraphRAG: a lista do hybrid seguida dos vizinhos no grafo, como o ask monta o contexto. */
    public static final String GRAPH_MODE = "hybrid+graph";

    private Evaluation() {}

    /** Item relevante: {@code symbol} nulo significa "qualquer símbolo do arquivo". */
    public record Relevant(String repo, String source, String symbol) {}

    public record Case(String id, String uc, String query, List<Relevant> relevant) {
        public Case {
            if (relevant.isEmpty()) {
                throw new CodeSearchException("invalid_request", "case " + id + " has no relevant items");
            }
        }
    }

    /**
     * Regra do gate. {@code min}/{@code max} comparam o valor absoluto; {@code minDeltaVs} (com {@code delta}) exige ganho
     * sobre outro modo no mesmo UC. {@code raw} guarda a regra como veio, para o relatório.
     */
    public record Rule(
            String uc, String mode, String metric, Double min, Double max, String minDeltaVs, Double delta,
            Map<String, Object> raw) {}

    public record Thresholds(int k, List<Rule> rules, String sha256) {}

    public record Failure(Rule rule, Double actual, Double baseline, String reason) {}

    /** modo → UC → métrica → valor (todas as métricas são Double, exceto {@code n}, Integer). */
    public record Report(int k, int cases, Map<String, Map<String, Map<String, Number>>> modes) {}

    public static boolean isRelevant(SearchResponse.Result result, List<Relevant> relevant) {
        var chunk = result.chunk();
        for (Relevant item : relevant) {
            if (chunk.repo().equals(item.repo()) && chunk.source().equals(item.source())
                    && (item.symbol() == null || chunk.symbol().equals(item.symbol()))) {
                return true;
            }
        }
        return false;
    }

    static double recallAtK(List<Boolean> flags, int totalRelevant, int k) {
        if (totalRelevant == 0) {
            return 0.0;
        }
        int hits = 0;
        for (int i = 0; i < Math.min(k, flags.size()); i++) {
            hits += flags.get(i) ? 1 : 0;
        }
        return (double) hits / totalRelevant;
    }

    static double reciprocalRank(List<Boolean> flags) {
        for (int i = 0; i < flags.size(); i++) {
            if (flags.get(i)) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    static double ndcgAtK(List<Boolean> flags, int totalRelevant, int k) {
        double dcg = 0;
        for (int i = 0; i < Math.min(k, flags.size()); i++) {
            if (flags.get(i)) {
                dcg += 1.0 / log2(i + 2);
            }
        }
        double ideal = 0;
        for (int i = 0; i < Math.min(totalRelevant, k); i++) {
            ideal += 1.0 / log2(i + 2);
        }
        return ideal == 0 ? 0.0 : dcg / ideal;
    }

    private static double log2(int x) {
        return Math.log(x) / Math.log(2);
    }

    /** Nearest-rank. */
    static double percentile(List<Double> values, double p) {
        if (values.isEmpty()) {
            return 0.0;
        }
        List<Double> ordered = new ArrayList<>(values);
        ordered.sort(null);
        return ordered.get(Math.max(0, (int) Math.ceil(p / 100 * ordered.size()) - 1));
    }

    private static double mean(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).sum() / values.size();
    }

    /** Roda cada caso em cada modo e agrega por UC e modo. */
    public static Report evaluate(
            Function<SearchService.Query, SearchResponse> search, List<Case> cases, List<String> modes, int k,
            List<String> repos) {
        return evaluate(search, cases, modes, k, repos, null);
    }

    /**
     * Com {@code graph} (trechos a mais para uma resposta do hybrid), mede também o modo {@link #GRAPH_MODE}. As métricas
     * por posição (recall@k, mrr, ndcg) não mudam, porque os vizinhos vêm depois dos k trechos; o ganho aparece em
     * {@code context_recall} (itens relevantes em todo o contexto, para comparar com o recall@k do hybrid) e o custo em
     * {@code context_size} e na latência (busca + expansão).
     */
    public static Report evaluate(
            Function<SearchService.Query, SearchResponse> search, List<Case> cases, List<String> modes, int k,
            List<String> repos, Function<SearchResponse, List<SearchResponse.Result>> graph) {
        List<String> runModes = new ArrayList<>(modes);
        if (graph != null) {
            runModes.add(GRAPH_MODE);
        }
        modes = runModes;
        String recallK = "recall@" + k;
        String ndcgK = "ndcg@" + k;
        Map<String, Map<String, Map<String, List<Double>>>> perMode = new LinkedHashMap<>();
        for (String mode : modes) {
            for (Case c : cases) {
                boolean withGraph = mode.equals(GRAPH_MODE);
                long started = System.nanoTime();
                SearchResponse response = search.apply(
                        new SearchService.Query(c.query(), withGraph ? "hybrid" : mode, repos, null, null, k, null));
                List<SearchResponse.Result> results = new ArrayList<>(response.results());
                if (withGraph) {
                    results.addAll(graph.apply(response));
                }
                double elapsedMs = (System.nanoTime() - started) / 1e6;
                List<Boolean> flags = new ArrayList<>();
                for (SearchResponse.Result r : results) {
                    flags.add(isRelevant(r, c.relevant()));
                }
                int total = c.relevant().size();
                Map<String, List<Double>> bucket = perMode.computeIfAbsent(mode, m -> new LinkedHashMap<>())
                        .computeIfAbsent(c.uc(), u -> {
                            Map<String, List<Double>> b = new LinkedHashMap<>();
                            for (String name : List.of("recall@1", "recall@5", recallK, "mrr", ndcgK, "latency_ms")) {
                                b.put(name, new ArrayList<>());
                            }
                            if (withGraph) {
                                b.put("context_recall", new ArrayList<>());
                                b.put("context_size", new ArrayList<>());
                            }
                            return b;
                        });
                if (withGraph) {
                    bucket.get("context_recall").add(recallAtK(flags, total, flags.size()));
                    bucket.get("context_size").add((double) flags.size());
                }
                bucket.get("recall@1").add(recallAtK(flags, total, 1));
                bucket.get("recall@5").add(recallAtK(flags, total, 5));
                bucket.get(recallK).add(recallAtK(flags, total, k));
                bucket.get("mrr").add(reciprocalRank(flags));
                bucket.get(ndcgK).add(ndcgAtK(flags, total, k));
                bucket.get("latency_ms").add(elapsedMs);
            }
        }
        Map<String, Map<String, Map<String, Number>>> report = new LinkedHashMap<>();
        perMode.forEach((mode, byUc) -> {
            Map<String, Map<String, Number>> rows = new LinkedHashMap<>();
            byUc.forEach((uc, metrics) -> {
                Map<String, Number> row = new LinkedHashMap<>();
                metrics.forEach((name, values) -> {
                    if (!name.equals("latency_ms")) {
                        row.put(name, mean(values));
                    }
                });
                row.put("n", metrics.get("mrr").size());
                row.put("latency_p50_ms", percentile(metrics.get("latency_ms"), 50));
                row.put("latency_p95_ms", percentile(metrics.get("latency_ms"), 95));
                rows.put(uc, row);
            });
            report.put(mode, rows);
        });
        return new Report(k, cases.size(), report);
    }

    private static Double metric(Report report, String mode, String uc, String metric) {
        Map<String, Number> row = report.modes().getOrDefault(mode, Map.of()).get(uc);
        Number value = row == null ? null : row.get(metric);
        return value == null ? null : value.doubleValue();
    }

    /** Devolve as regras que falharam (vazio = gate aprovado). */
    public static List<Failure> checkThresholds(Report report, Thresholds thresholds) {
        List<Failure> failures = new ArrayList<>();
        for (Rule rule : thresholds.rules()) {
            Double actual = metric(report, rule.mode(), rule.uc(), rule.metric());
            if (actual == null) {
                failures.add(new Failure(rule, null, null, "missing"));
                continue;
            }
            if (rule.min() != null && actual < rule.min()) {
                failures.add(new Failure(rule, actual, null, null));
            }
            if (rule.max() != null && actual > rule.max()) {
                failures.add(new Failure(rule, actual, null, null));
            }
            if (rule.minDeltaVs() != null) {
                Double base = metric(report, rule.minDeltaVs(), rule.uc(), rule.metric());
                if (base == null || actual - base < rule.delta()) {
                    failures.add(new Failure(rule, actual, base, null));
                }
            }
        }
        return failures;
    }
}
