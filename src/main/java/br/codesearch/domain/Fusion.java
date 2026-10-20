package br.codesearch.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Fusão RRF e colapso de chunks sobrepostos. */
public final class Fusion {

    public static final int RRF_K = 60;

    private Fusion() {}

    public record Location(String source, int startLine, int endLine) {}

    /** Reciprocal Rank Fusion: usa só posições, então dispensa calibrar scores entre sinais. */
    public static List<Hit> rrf(List<List<Hit>> rankings, int k) {
        record Key(String repoId, int row) {}
        Map<Key, Double> fused = new HashMap<>();
        for (List<Hit> ranking : rankings) {
            int rank = 1;
            for (Hit hit : ranking) {
                fused.merge(new Key(hit.repoId(), hit.row()), 1.0 / (k + rank), Double::sum);
                rank++;
            }
        }
        List<Map.Entry<Key, Double>> ordered = new ArrayList<>(fused.entrySet());
        ordered.sort(Comparator.<Map.Entry<Key, Double>>comparingDouble(e -> -e.getValue())
                .thenComparing(e -> e.getKey().repoId())
                .thenComparingInt(e -> e.getKey().row()));
        List<Hit> out = new ArrayList<>(ordered.size());
        for (Map.Entry<Key, Double> e : ordered) {
            out.add(new Hit(e.getKey().repoId(), e.getKey().row(), e.getValue()));
        }
        return out;
    }

    public static List<Hit> rrf(List<List<Hit>> rankings) {
        return rrf(rankings, RRF_K);
    }

    /** Mantém o melhor de cada grupo de chunks sobrepostos (mesmo repo e arquivo), até {@code limit}. */
    public static List<Hit> collapseOverlaps(List<Hit> hits, Function<Hit, Location> locate, int limit) {
        List<Hit> kept = new ArrayList<>();
        Map<String, List<int[]>> spans = new HashMap<>();
        for (Hit hit : hits) {
            Location loc = locate.apply(hit);
            String key = hit.repoId() + "\u0000" + loc.source();
            List<int[]> existing = spans.computeIfAbsent(key, x -> new ArrayList<>());
            boolean overlaps = false;
            for (int[] span : existing) {
                if (loc.startLine() <= span[1] && span[0] <= loc.endLine()) {
                    overlaps = true;
                    break;
                }
            }
            if (overlaps) {
                continue;
            }
            existing.add(new int[] {loc.startLine(), loc.endLine()});
            kept.add(hit);
            if (kept.size() >= limit) {
                break;
            }
        }
        return kept;
    }
}
