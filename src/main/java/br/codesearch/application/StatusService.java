package br.codesearch.application;

import br.codesearch.application.port.Embedder;
import br.codesearch.application.port.IndexSnapshot;
import br.codesearch.application.port.RepoRegistry;
import br.codesearch.application.port.VectorStore;
import br.codesearch.domain.CodeSearchException;
import br.codesearch.domain.ExclusionPolicy;
import br.codesearch.domain.RepoEntry;
import br.codesearch.domain.SegmentInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** Estado do índice e dos repositórios (sem caminhos locais) e a checagem de prontidão. */
public final class StatusService {

    public record Ready(boolean ok, Map<String, String> checks) {}

    private final VectorStore store;
    private final RepoRegistry registry;
    private final ExclusionPolicy policy;
    private final Supplier<Embedder> embedder;

    /** {@code embedder} pode devolver null ou falhar: o status não deve depender do Ollama no ar. */
    public StatusService(VectorStore store, RepoRegistry registry, ExclusionPolicy policy, Supplier<Embedder> embedder) {
        this.store = store;
        this.registry = registry;
        this.policy = policy;
        this.embedder = embedder;
    }

    private Embedder embedderOrNull() {
        try {
            return embedder.get();
        } catch (CodeSearchException e) {
            return null;
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> pol = new LinkedHashMap<>();
        pol.put("valid", policy.valid());
        pol.put("version_id", policy.versionId());
        pol.put("reason", policy.reason());
        out.put("policy", pol);
        Embedder e = embedderOrNull();
        if (e != null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("model", e.profile().model());
            m.put("dim", e.profile().dim());
            m.put("profile_id", e.profile().profileId());
            m.put("local", e.local());
            out.put("embedder", m);
        } else {
            out.put("embedder", null);
        }
        IndexSnapshot snapshot = null;
        out.put("index", null);
        try {
            snapshot = store.openActive().orElse(null);
        } catch (CodeSearchException ex) {
            out.put("index", Map.of("error", ex.code()));
        }
        if (snapshot != null) {
            Map<String, Object> idx = new LinkedHashMap<>();
            idx.put("generation", snapshot.generationId());
            idx.put("previous_generation", store.previousGenerationId().orElse(null));
            idx.put("created_at", snapshot.createdAt());
            idx.put("profile_id", snapshot.profile().profileId());
            idx.put("model", snapshot.profile().model());
            idx.put("dim", snapshot.profile().dim());
            idx.put("schema", snapshot.schema().toMap());
            out.put("index", idx);
        }
        List<Map<String, Object>> repos = new ArrayList<>();
        for (RepoEntry entry : registry.list()) {
            Optional<SegmentInfo> seg = snapshot == null ? Optional.empty() : snapshot.segmentInfo(entry.repoId());
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("repo_id", entry.repoId());
            r.put("enabled", entry.enabled());
            r.put("indexed", seg.isPresent());
            r.put("repo_revision", seg.map(SegmentInfo::repoRevision).orElse(null));
            r.put("indexed_at", seg.map(SegmentInfo::indexedAt).orElse(null));
            r.put("chunks", seg.map(SegmentInfo::chunks).orElse(0));
            repos.add(r);
        }
        out.put("repos", repos);
        return out;
    }

    /** Modelo disponível, índice íntegro e perfil compatível. */
    public Ready ready() {
        Map<String, String> checks = new LinkedHashMap<>();
        checks.put("embedder", "ok");
        checks.put("index", "ok");
        checks.put("profile", "ok");
        Embedder e = embedderOrNull();
        if (e == null) {
            checks.put("embedder", "unavailable");
        }
        IndexSnapshot snapshot = null;
        try {
            snapshot = store.openActive().orElse(null);
            if (snapshot == null) {
                checks.put("index", "no_index");
            }
        } catch (CodeSearchException ex) {
            checks.put("index", ex.code());
        }
        if (snapshot != null && e != null) {
            if (!snapshot.profile().profileId().equals(e.profile().profileId())) {
                checks.put("profile", "mismatch");
            }
        } else {
            checks.put("profile", "unknown");
        }
        return new Ready(checks.values().stream().allMatch("ok"::equals), checks);
    }
}
