package br.codesearch.application;

import br.codesearch.application.IndexReport.RepoReport;
import br.codesearch.application.port.ChunkDescriber;
import br.codesearch.application.port.EmbeddingCache;
import br.codesearch.application.port.Embedder;
import br.codesearch.application.port.IndexSnapshot;
import br.codesearch.application.port.RepoRegistry;
import br.codesearch.application.port.SourceReader;
import br.codesearch.application.port.SourceReaderFactory;
import br.codesearch.application.port.SparseEncoder;
import br.codesearch.application.port.VectorStore;
import br.codesearch.domain.BlobEntry;
import br.codesearch.domain.ChunkDraft;
import br.codesearch.domain.ChunkIds;
import br.codesearch.domain.ChunkRecord;
import br.codesearch.domain.CodeSearchException;
import br.codesearch.domain.EmbeddingProfile;
import br.codesearch.domain.ExclusionPolicy;
import br.codesearch.domain.IndexSchema;
import br.codesearch.domain.JavaChunker;
import br.codesearch.domain.RepoEntry;
import br.codesearch.domain.SegmentInfo;
import br.codesearch.domain.SegmentRef;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** Indexação incremental: um segmento por repositório, publicado de forma atômica em uma nova geração. */
public final class IndexService {

    private static final DateTimeFormatter UTC_SECONDS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private final VectorStore store;
    private final RepoRegistry registry;
    private final Embedder embedder;
    private final SparseEncoder sparse;
    private final ExclusionPolicy policy;
    private final EmbeddingCache cache;
    private final int batchSize;
    private final Supplier<String> now;
    private final SourceReaderFactory sources;
    private final ChunkDescriber describer;

    public IndexService(
            VectorStore store, RepoRegistry registry, Embedder embedder, SparseEncoder sparse, ExclusionPolicy policy,
            EmbeddingCache cache, SourceReaderFactory sources, ChunkDescriber describer, int batchSize,
            Supplier<String> now) {
        this.store = store;
        this.registry = registry;
        this.embedder = embedder;
        this.sparse = sparse;
        this.policy = policy;
        this.cache = cache;
        this.sources = sources;
        this.describer = describer;
        this.batchSize = batchSize;
        this.now = now;
    }

    public IndexService(
            VectorStore store, RepoRegistry registry, Embedder embedder, SparseEncoder sparse, ExclusionPolicy policy,
            EmbeddingCache cache, SourceReaderFactory sources, ChunkDescriber describer) {
        this(store, registry, embedder, sparse, policy, cache, sources, describer, 32,
                () -> UTC_SECONDS.format(Instant.now()));
    }

    private String describerId() {
        return describer == null ? "" : describer.describerId();
    }

    public IndexSchema schema() {
        return new IndexSchema(JavaChunker.CHUNKER_VERSION, sparse.version(), policy.versionId(), 1.2, 0.75, describerId());
    }

    public IndexReport index(List<String> repoIds, boolean force) {
        if (!policy.valid()) {
            return new IndexReport("skipped", policy.reason());
        }
        Map<String, RepoEntry> enabled = new LinkedHashMap<>();
        for (RepoEntry e : registry.enabled()) {
            enabled.put(e.repoId(), e);
        }
        List<RepoEntry> targets = new ArrayList<>();
        if (repoIds == null) {
            targets.addAll(enabled.values());
        } else {
            for (String id : repoIds) {
                if (!enabled.containsKey(id)) {
                    throw new CodeSearchException("invalid_request", "repository is unknown or disabled");
                }
                targets.add(enabled.get(id));
            }
        }

        Optional<IndexSnapshot> snapshot = store.openActive();
        List<SegmentRef> refs = new ArrayList<>();
        List<RepoReport> reports = new ArrayList<>();
        for (RepoEntry entry : targets) {
            if (!force && isCurrent(snapshot, entry)) {
                RepoReport report = new RepoReport(entry.repoId());
                report.revision = snapshot.get().segmentInfo(entry.repoId()).orElseThrow().repoRevision();
                report.unchanged = true;
                reports.add(report);
                continue;
            }
            RepoReport report = new RepoReport(entry.repoId());
            refs.add(indexRepo(entry, report));
            reports.add(report);
        }

        List<String> remove = new ArrayList<>();
        if (repoIds == null && snapshot.isPresent()) {
            for (String repo : snapshot.get().repos()) {
                if (!enabled.containsKey(repo)) {
                    remove.add(repo);
                }
            }
        }
        if (refs.isEmpty() && remove.isEmpty() && snapshot.isPresent()) {
            return new IndexReport("unchanged", "", snapshot.get().generationId(), reports, List.of());
        }
        String generation = store.publish(embedder.profile(), schema(), refs, remove);
        return new IndexReport("published", "", generation, reports, remove);
    }

    public IndexReport index() {
        return index(null, false);
    }

    private boolean isCurrent(Optional<IndexSnapshot> snapshot, RepoEntry entry) {
        Optional<SegmentInfo> info = snapshot.flatMap(s -> s.segmentInfo(entry.repoId()));
        if (info.isEmpty()) {
            return false;
        }
        SegmentInfo i = info.get();
        return i.repoRevision().equals(sources.open(entry).revision())
                && i.roots().equals(entry.roots())
                && i.profileId().equals(embedder.profile().profileId())
                && i.schemaId().equals(schema().schemaId());
    }

    private SegmentRef indexRepo(RepoEntry entry, RepoReport report) {
        SourceReader git = sources.open(entry);
        report.revision = git.revision();
        List<BlobEntry> blobs = git.listBlobs(entry.roots());
        report.filesSeen = blobs.size();

        List<BlobEntry> allowed = new ArrayList<>();
        for (BlobEntry b : blobs) {
            if (policy.allows(b.path())) { // decidido antes de abrir qualquer arquivo
                allowed.add(b);
            }
        }
        report.filesSkippedPolicy = blobs.size() - allowed.size();
        Map<String, List<String>> pathOf = new LinkedHashMap<>();
        for (BlobEntry b : allowed) {
            pathOf.computeIfAbsent(b.sha(), k -> new ArrayList<>()).add(b.path());
        }

        List<ChunkDraft> drafts = new ArrayList<>();
        for (SourceReader.Blob blob : git.readBlobs(new ArrayList<>(pathOf.keySet()))) {
            for (String path : pathOf.get(blob.sha())) {
                if (blob.content().length > policy.maxFileBytes()) {
                    report.filesSkippedOther++;
                    continue;
                }
                String text = decodeUtf8(blob.content());
                if (text == null) {
                    report.filesSkippedOther++;
                    continue;
                }
                report.filesIndexed++;
                for (ChunkDraft draft : JavaChunker.chunkJava(path, text)) {
                    if (policy.containsSecret(draft.text())) {
                        report.chunksSkippedSecret++;
                    } else {
                        drafts.add(draft);
                    }
                }
            }
        }
        drafts.sort(Comparator.comparing(ChunkDraft::source)
                .thenComparingInt(ChunkDraft::startLine)
                .thenComparingInt(ChunkDraft::endLine)
                .thenComparing(ChunkDraft::symbol));
        report.chunks = drafts.size();

        String describerId = describerId();
        List<String> keys = new ArrayList<>();
        for (ChunkDraft d : drafts) {
            keys.add(d.embedKey(entry.repoId(), describerId));
        }
        Map<String, float[]> vectors = embed(entry.repoId(), drafts, keys, report);
        float[][] dense = new float[keys.size()][];
        for (int i = 0; i < keys.size(); i++) {
            dense[i] = vectors.get(keys.get(i));
        }

        EmbeddingProfile profile = embedder.profile();
        List<String> lexicalTexts = new ArrayList<>();
        for (ChunkDraft d : drafts) {
            lexicalTexts.add(d.source() + "\n" + d.symbol() + "\n" + d.text());
        }
        List<Map<String, Integer>> lexical = sparse.encodeDocuments(lexicalTexts);
        List<ChunkRecord> records = new ArrayList<>();
        for (int i = 0; i < drafts.size(); i++) {
            ChunkDraft d = drafts.get(i);
            records.add(new ChunkRecord(
                    ChunkIds.makeChunkId(profile.profileId(), entry.repoId(), d.source(), d.symbol(), d.contentHash(), d.startLine()),
                    d.source(), "java", d.symbol(), d.parentSymbol(), d.startLine(), d.endLine(), d.text(), lexical.get(i)));
        }
        return store.writeSegment(entry.repoId(), report.revision, now.get(), profile, schema(), records, dense, entry.roots());
    }

    /** UTF-8 estrito, aceitando BOM no início (utf-8-sig); nulo se os bytes não forem UTF-8 válido. */
    static String decodeUtf8(byte[] raw) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw))
                    .toString();
            return text.startsWith("﻿") ? text.substring(1) : text;
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private Map<String, float[]> embed(String repoId, List<ChunkDraft> drafts, List<String> keys, RepoReport report) {
        Map<String, float[]> found = new HashMap<>(cache.lookup(keys));
        report.embeddingsCached = found.size();
        Map<String, ChunkDraft> pendingDraft = new LinkedHashMap<>();
        for (int i = 0; i < drafts.size(); i++) {
            String key = keys.get(i);
            if (!found.containsKey(key) && !pendingDraft.containsKey(key)) {
                pendingDraft.put(key, drafts.get(i));
            }
        }
        List<String> pending = new ArrayList<>(pendingDraft.keySet());
        int dim = embedder.profile().dim();
        for (int i = 0; i < pending.size(); i += batchSize) {
            List<String> batch = pending.subList(i, Math.min(pending.size(), i + batchSize));
            List<String> texts = new ArrayList<>();
            for (String key : batch) { // descreve só o que não está no cache
                texts.add(embedText(repoId, pendingDraft.get(key)));
            }
            List<float[]> mat = embedder.embedDocuments(texts);
            Map<String, float[]> fresh = new HashMap<>();
            if (mat.size() != batch.size()) {
                throw invalidOutput();
            }
            for (int j = 0; j < batch.size(); j++) {
                float[] v = mat.get(j);
                if (v == null || v.length != dim || !validNorm(v)) {
                    throw invalidOutput();
                }
                fresh.put(batch.get(j), v);
            }
            cache.add(fresh); // progresso parcial não se perde se um lote seguinte falhar
            found.putAll(fresh);
            report.embeddingsNew += batch.size();
        }
        return found;
    }

    private String embedText(String repoId, ChunkDraft draft) {
        String description = describer == null ? "" : describer.describe(draft.symbol(), draft.text());
        return draft.embedText(repoId, description);
    }

    private static boolean validNorm(float[] v) {
        double sum = 0;
        for (float x : v) {
            if (!Float.isFinite(x)) {
                return false;
            }
            sum += (double) x * x;
        }
        return Math.sqrt(sum) >= 1e-6;
    }

    private static CodeSearchException invalidOutput() {
        return new CodeSearchException("embedder_invalid_output", "embedder returned null or malformed vectors");
    }
}
