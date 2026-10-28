package br.codesearch.adapter.out.file;

import br.codesearch.application.port.IndexSnapshot;
import br.codesearch.domain.ChunkView;
import br.codesearch.domain.CodeSearchException;
import br.codesearch.domain.EmbeddingProfile;
import br.codesearch.domain.Filters;
import br.codesearch.domain.Hit;
import br.codesearch.domain.IndexSchema;
import br.codesearch.domain.Location;
import br.codesearch.domain.SegmentInfo;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** Visão consistente de uma geração publicada; uma por requisição. */
final class FileSnapshot implements IndexSnapshot {

    private static final Set<String> MODES = Set.of("hybrid", "semantic", "lexical");

    private final String generationId;
    private final String createdAt;
    private final EmbeddingProfile profile;
    private final IndexSchema schema;
    private final Map<String, StoredSegment> segments;

    FileSnapshot(String generationId, String createdAt, EmbeddingProfile profile, IndexSchema schema, Map<String, StoredSegment> segments) {
        this.generationId = generationId;
        this.createdAt = createdAt;
        this.profile = profile;
        this.schema = schema;
        this.segments = new TreeMap<>(segments);
    }

    @Override
    public String generationId() {
        return generationId;
    }

    @Override
    public String createdAt() {
        return createdAt;
    }

    @Override
    public EmbeddingProfile profile() {
        return profile;
    }

    @Override
    public IndexSchema schema() {
        return schema;
    }

    @Override
    public List<String> repos() {
        return List.copyOf(segments.keySet());
    }

    @Override
    public Optional<SegmentInfo> segmentInfo(String repoId) {
        return Optional.ofNullable(segments.get(repoId)).map(StoredSegment::info);
    }

    @Override
    public Map<String, List<Hit>> search(
            List<String> repos, float[] denseQuery, Collection<String> lexicalTerms, Filters filters, String mode, int depth) {
        if (!MODES.contains(mode)) {
            throw new CodeSearchException("invalid_request", "unknown mode");
        }
        boolean useDense = !mode.equals("lexical");
        boolean useLexical = !mode.equals("semantic");
        float[] q = null;
        if (useDense) {
            if (denseQuery == null) {
                throw new CodeSearchException("invalid_request", "dense query vector required");
            }
            if (denseQuery.length != profile.dim()) {
                throw new CodeSearchException("profile_mismatch", "query vector dimension differs from the index");
            }
            double sum = 0;
            for (float x : denseQuery) {
                sum += (double) x * x;
            }
            double norm = Math.sqrt(sum);
            if (!Double.isFinite(norm) || norm < 1e-6) {
                throw new CodeSearchException("embedder_invalid_output", "query vector is null");
            }
            q = new float[denseQuery.length];
            for (int i = 0; i < q.length; i++) {
                q[i] = (float) (denseQuery[i] / norm);
            }
        }
        Collection<String> terms = useLexical && lexicalTerms != null ? lexicalTerms : List.of();
        List<Hit> dense = new ArrayList<>();
        List<Hit> lexical = new ArrayList<>();
        for (String repo : repos) {
            StoredSegment seg = segments.get(repo);
            if (seg == null) {
                continue;
            }
            boolean[] mask = seg.rowMask(filters);
            if (q != null) {
                dense.addAll(seg.searchDense(q, depth, mask));
            }
            if (!terms.isEmpty()) {
                lexical.addAll(seg.searchLexical(terms, depth, mask));
            }
        }
        Comparator<Hit> order = Comparator.comparingDouble((Hit h) -> -h.score())
                .thenComparing(Hit::repoId).thenComparingInt(Hit::row);
        dense.sort(order);
        lexical.sort(order);
        return Map.of(
                "dense", dense.size() > depth ? List.copyOf(dense.subList(0, depth)) : List.copyOf(dense),
                "lexical", lexical.size() > depth ? List.copyOf(lexical.subList(0, depth)) : List.copyOf(lexical));
    }

    @Override
    public Location locate(Hit hit) {
        return segments.get(hit.repoId()).locate(hit.row());
    }

    @Override
    public ChunkView record(Hit hit) {
        return segments.get(hit.repoId()).record(hit.row());
    }

    @Override
    public List<ChunkView> records(String repoId) {
        StoredSegment seg = segments.get(repoId);
        return seg == null ? List.of() : seg.records();
    }
}
