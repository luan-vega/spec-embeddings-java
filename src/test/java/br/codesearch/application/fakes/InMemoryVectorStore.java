package br.codesearch.application.fakes;

import br.codesearch.application.port.IndexSnapshot;
import br.codesearch.application.port.VectorStore;
import br.codesearch.domain.ChunkRecord;
import br.codesearch.domain.ChunkView;
import br.codesearch.domain.EmbeddingProfile;
import br.codesearch.domain.Filters;
import br.codesearch.domain.Hit;
import br.codesearch.domain.IndexSchema;
import br.codesearch.domain.Location;
import br.codesearch.domain.SegmentInfo;
import br.codesearch.domain.SegmentRef;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Armazenamento em memória para testar os casos de uso. O ranking é simples: produto escalar e BM25. */
public class InMemoryVectorStore implements VectorStore {

    record Segment(
            String repoId, String revision, String indexedAt, List<String> roots, EmbeddingProfile profile, IndexSchema schema,
            List<ChunkRecord> chunks, float[][] dense) {}

    private final Map<String, Segment> written = new HashMap<>();
    private final List<Map<String, Segment>> generations = new ArrayList<>();
    private final EmbeddingProfileHolder holder = new EmbeddingProfileHolder();
    private int counter;

    private static final class EmbeddingProfileHolder {
        EmbeddingProfile profile;
        IndexSchema schema;
    }

    @Override
    public Optional<IndexSnapshot> openActive() {
        if (generations.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Snapshot("g" + generations.size(), generations.get(generations.size() - 1), holder.profile, holder.schema));
    }

    @Override
    public SegmentRef writeSegment(
            String repoId, String repoRevision, String indexedAt, EmbeddingProfile profile, IndexSchema schema,
            List<ChunkRecord> chunks, float[][] dense, List<String> roots) {
        String id = repoId + "-" + (++counter);
        written.put(id, new Segment(repoId, repoRevision, indexedAt, List.copyOf(roots), profile, schema, chunks, dense));
        return new SegmentRef(repoId, id, "sha");
    }

    @Override
    public String publish(EmbeddingProfile profile, IndexSchema schema, List<SegmentRef> replace, List<String> remove) {
        Map<String, Segment> next = new LinkedHashMap<>(generations.isEmpty() ? Map.of() : generations.get(generations.size() - 1));
        for (SegmentRef ref : replace) {
            next.put(ref.repoId(), written.get(ref.segmentId()));
        }
        remove.forEach(next::remove);
        generations.add(next);
        holder.profile = profile;
        holder.schema = schema;
        return "g" + generations.size();
    }

    @Override
    public String rollback() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Map<String, Integer> prune(double tmpMaxAgeSeconds) {
        return Map.of();
    }

    @Override
    public Optional<String> previousGenerationId() {
        return generations.size() > 1 ? Optional.of("g" + (generations.size() - 1)) : Optional.empty();
    }

    private record Snapshot(String generationId, Map<String, Segment> segments, EmbeddingProfile profile, IndexSchema schema)
            implements IndexSnapshot {

        @Override
        public String createdAt() {
            return "now";
        }

        @Override
        public List<String> repos() {
            return new ArrayList<>(segments.keySet());
        }

        @Override
        public Optional<SegmentInfo> segmentInfo(String repoId) {
            Segment s = segments.get(repoId);
            return s == null ? Optional.empty() : Optional.of(new SegmentInfo(
                    repoId, s.revision(), s.indexedAt(), s.roots(), s.profile().profileId(), s.schema().schemaId(), s.chunks().size()));
        }

        private boolean matches(ChunkRecord c, Filters f) {
            if (f.languages() != null && !f.languages().contains(c.language())) {
                return false;
            }
            if (f.pathPrefix().isEmpty()) {
                return true;
            }
            List<String> parts = List.of(c.source().split("/"));
            return parts.size() >= f.pathPrefix().size() && parts.subList(0, f.pathPrefix().size()).equals(f.pathPrefix());
        }

        @Override
        public Map<String, List<Hit>> search(
                List<String> repos, float[] denseQuery, Collection<String> lexicalTerms, Filters filters, String mode, int depth) {
            List<Hit> dense = new ArrayList<>();
            List<Hit> lexical = new ArrayList<>();
            for (String repo : repos) {
                Segment s = segments.get(repo);
                double avg = s.chunks().stream().mapToInt(c -> c.terms().values().stream().mapToInt(Integer::intValue).sum()).average().orElse(1);
                for (int row = 0; row < s.chunks().size(); row++) {
                    ChunkRecord c = s.chunks().get(row);
                    if (!matches(c, filters)) {
                        continue;
                    }
                    if (denseQuery != null) {
                        double dot = 0;
                        for (int i = 0; i < denseQuery.length; i++) {
                            dot += denseQuery[i] * s.dense()[row][i];
                        }
                        dense.add(new Hit(repo, row, dot));
                    }
                    double score = 0;
                    int len = c.terms().values().stream().mapToInt(Integer::intValue).sum();
                    for (String term : lexicalTerms) {
                        int tf = c.terms().getOrDefault(term, 0);
                        if (tf > 0) {
                            double k1 = s.schema().bm25K1();
                            double b = s.schema().bm25B();
                            score += tf * (k1 + 1) / (tf + k1 * (1 - b + b * len / avg));
                        }
                    }
                    if (score > 0) {
                        lexical.add(new Hit(repo, row, score));
                    }
                }
            }
            Comparator<Hit> byScore = Comparator.comparingDouble(Hit::score).reversed()
                    .thenComparing(Hit::repoId).thenComparingInt(Hit::row);
            dense.sort(byScore);
            lexical.sort(byScore);
            return Map.of(
                    "dense", dense.subList(0, Math.min(depth, dense.size())),
                    "lexical", lexical.subList(0, Math.min(depth, lexical.size())));
        }

        @Override
        public Location locate(Hit hit) {
            ChunkRecord c = segments.get(hit.repoId()).chunks().get(hit.row());
            return new Location(c.source(), c.startLine(), c.endLine());
        }

        @Override
        public ChunkView record(Hit hit) {
            Segment s = segments.get(hit.repoId());
            ChunkRecord c = s.chunks().get(hit.row());
            return new ChunkView(hit.repoId(), c.source(), c.symbol(), c.parentSymbol(), c.startLine(), c.endLine(),
                    c.language(), c.text(), s.revision(), s.indexedAt());
        }
    }
}
