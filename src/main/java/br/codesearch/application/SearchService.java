package br.codesearch.application;

import br.codesearch.application.SearchResponse.Coverage;
import br.codesearch.application.SearchResponse.IndexInfo;
import br.codesearch.application.SearchResponse.Result;
import br.codesearch.application.SearchResponse.Unavailable;
import br.codesearch.application.port.Embedder;
import br.codesearch.application.port.IndexSnapshot;
import br.codesearch.application.port.RepoRegistry;
import br.codesearch.application.port.SparseEncoder;
import br.codesearch.application.port.VectorStore;
import br.codesearch.domain.CodeSearchException;
import br.codesearch.domain.EmbeddingProfile;
import br.codesearch.domain.Filters;
import br.codesearch.domain.Fusion;
import br.codesearch.domain.Hit;
import br.codesearch.domain.JavaChunker;
import br.codesearch.domain.RepoEntry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Valida, resolve repositórios, checa o perfil, embeda a consulta, funde os sinais e responde. */
public final class SearchService {

    public static final List<String> MODES = List.of("hybrid", "semantic", "lexical");

    /** Parâmetros de uma busca. {@code repos}, {@code languages} e {@code pathPrefix} podem ser nulos. */
    public record Query(
            String text, String mode, List<String> repos, List<String> languages, String pathPrefix, int topK,
            String requestId) {

        public static Query of(String text) {
            return new Query(text, "hybrid", null, null, null, 10, null);
        }

        public Query withMode(String newMode) {
            return new Query(text, newMode, repos, languages, pathPrefix, topK, requestId);
        }

        public Query withTopK(int newTopK) {
            return new Query(text, mode, repos, languages, pathPrefix, newTopK, requestId);
        }

        public Query withRepos(List<String> newRepos) {
            return new Query(text, mode, newRepos, languages, pathPrefix, topK, requestId);
        }
    }

    private final VectorStore store;
    private final RepoRegistry registry;
    private final Embedder embedder;
    private final SparseEncoder sparse;
    private final int maxQueryChars;
    private final int maxTopK;
    private final int maxRepos;
    private final int rrfK;

    public SearchService(VectorStore store, RepoRegistry registry, Embedder embedder, SparseEncoder sparse) {
        this(store, registry, embedder, sparse, 2000, 50, 20, Fusion.RRF_K);
    }

    public SearchService(
            VectorStore store, RepoRegistry registry, Embedder embedder, SparseEncoder sparse, int maxQueryChars,
            int maxTopK, int maxRepos, int rrfK) {
        this.store = store;
        this.registry = registry;
        this.embedder = embedder;
        this.sparse = sparse;
        this.maxQueryChars = maxQueryChars;
        this.maxTopK = maxTopK;
        this.maxRepos = maxRepos;
        this.rrfK = rrfK;
    }

    public SearchResponse search(Query q) {
        String requestId = q.requestId() != null ? q.requestId() : UUID.randomUUID().toString().replace("-", "");
        String query = q.text();
        if (query == null || query.isBlank() || query.length() > maxQueryChars) {
            throw new CodeSearchException("invalid_request", "query is empty or too long");
        }
        String mode = q.mode();
        if (!MODES.contains(mode)) {
            throw new CodeSearchException("invalid_request", "mode must be hybrid, semantic or lexical");
        }
        int topK = q.topK();
        if (topK < 1 || topK > maxTopK) {
            throw new CodeSearchException("invalid_request", "top_k out of range");
        }
        if (q.repos() != null && (q.repos().isEmpty() || q.repos().size() > maxRepos)) {
            throw new CodeSearchException("invalid_request", "invalid number of repos");
        }
        Filters filters = Filters.make(q.languages(), q.pathPrefix());

        Map<String, RepoEntry> registered = new LinkedHashMap<>();
        for (RepoEntry e : registry.list()) {
            registered.put(e.repoId(), e);
        }
        List<String> requested = new ArrayList<>();
        if (q.repos() != null) {
            requested.addAll(new LinkedHashSet<>(q.repos()));
        } else {
            registered.forEach((id, e) -> {
                if (e.enabled()) {
                    requested.add(id);
                }
            });
        }
        IndexSnapshot snapshot = store.openActive().orElse(null);

        List<Unavailable> unavailable = new ArrayList<>();
        List<String> searchable = new ArrayList<>();
        for (String repo : requested) {
            RepoEntry entry = registered.get(repo);
            if (entry == null) {
                unavailable.add(new Unavailable(repo, "unknown_repo"));
            } else if (!entry.enabled()) {
                unavailable.add(new Unavailable(repo, "disabled"));
            } else if (snapshot == null || snapshot.segmentInfo(repo).isEmpty()) {
                unavailable.add(new Unavailable(repo, "no_index"));
            } else {
                searchable.add(repo);
            }
        }
        if (searchable.isEmpty() || snapshot == null) {
            throw new CodeSearchException("no_repos_available", "none of the requested repositories has an index");
        }

        EmbeddingProfile profile = embedder.profile();
        if (!snapshot.profile().profileId().equals(profile.profileId())
                || !snapshot.schema().tokenizerVersion().equals(sparse.version())
                || !snapshot.schema().chunkerVersion().equals(JavaChunker.CHUNKER_VERSION)) {
            throw new CodeSearchException("profile_mismatch", "the active index was built with a different profile or schema");
        }

        float[] denseQuery = mode.equals("hybrid") || mode.equals("semantic") ? embedder.embedQuery(query) : null;
        Set<String> terms = mode.equals("hybrid") || mode.equals("lexical")
                ? sparse.encodeQuery(query).keySet() : Set.of();
        int depth = Math.max(100, 5 * topK);
        Map<String, List<Hit>> rankings = snapshot.search(searchable, denseQuery, terms, filters, mode, depth);

        List<Hit> fused = Fusion.rrf(List.of(
                rankings.getOrDefault("dense", List.of()), rankings.getOrDefault("lexical", List.of())), rrfK);
        List<Hit> top = Fusion.collapseOverlaps(fused, snapshot::locate, topK);
        List<Result> results = new ArrayList<>();
        for (Hit hit : top) {
            results.add(new Result(snapshot.record(hit), hit.score()));
        }
        String status = unavailable.isEmpty() ? "complete" : "partial";
        return new SearchResponse(
                status, results, new Coverage(searchable, unavailable),
                new IndexInfo(snapshot.profile().profileId(), snapshot.generationId()), requestId);
    }
}
