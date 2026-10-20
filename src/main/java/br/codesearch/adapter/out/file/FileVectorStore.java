package br.codesearch.adapter.out.file;

import br.codesearch.application.port.IndexSnapshot;
import br.codesearch.application.port.VectorStore;
import br.codesearch.domain.ChunkRecord;
import br.codesearch.domain.CodeSearchException;
import br.codesearch.domain.EmbeddingProfile;
import br.codesearch.domain.IndexSchema;
import br.codesearch.domain.SegmentRef;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Segmentos imutáveis por repo, agrupados em gerações publicadas atomicamente (blue/green). Layout sob {@code root}:
 *
 * <pre>
 * active.json / previous.json   ponteiros {"generation", "sha256"}
 * generations/&lt;id&gt;.json        perfil, schema e lista de segmentos
 * segments/&lt;segment_id&gt;/        arrays binários, chunks.jsonl, vocab.json, manifest.json
 * tmp/                          staging no mesmo volume (rename atômico)
 * write.lock                    trava dos escritores
 * </pre>
 *
 * Leitores não usam trava: leem {@code active.json} uma vez e trabalham sobre arquivos imutáveis. O formato dos
 * arrays é binário big-endian próprio desta versão (não é o .npy da versão Python).
 */
public final class FileVectorStore implements VectorStore {

    private static final Pattern REPO_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    private static final Pattern GENERATION_ID = Pattern.compile("[0-9TZ]+-[0-9a-f]{8}");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter COMPACT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final List<String> SEGMENT_FILES = List.of(
            "dense.bin", "ids.bin", "source_idx.bin", "lang_idx.bin", "start_line.bin", "end_line.bin", "sources.json",
            "languages.json", "chunks.jsonl", "chunks.offsets.bin", "vocab.json", "df.bin", "postings_offsets.bin",
            "postings_doc.bin", "postings_tf.bin", "doc_len.bin");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonMapper SORTED = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).enable(SerializationFeature.INDENT_OUTPUT).build();

    /** Segmento ausente durante a leitura: publicação e prune concorrentes; quem chama relê o ponteiro. */
    private static final class Missing extends RuntimeException {
        Missing() {
            super(null, null, false, false);
        }
    }

    private final Path root;
    private final Duration lockTimeout;
    private final Map<String, StoredSegment> loaded = new ConcurrentHashMap<>();

    public FileVectorStore(Path root) {
        this(root, Duration.ofSeconds(10));
    }

    public FileVectorStore(Path root, Duration lockTimeout) {
        this.root = root;
        this.lockTimeout = lockTimeout;
        try {
            for (String sub : List.of("generations", "segments", "tmp")) {
                Files.createDirectories(root.resolve(sub));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static CodeSearchException unavailable(String message) {
        return new CodeSearchException("vector_store_unavailable", message);
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(Path file) {
        try {
            return sha256(Files.readAllBytes(file));
        } catch (IOException e) {
            throw unavailable("segment file integrity check failed");
        }
    }

    private FileLock writeLock() {
        return FileLock.acquire(root.resolve("write.lock"), lockTimeout);
    }

    // ------------------------------------------------------------------ escrita

    @Override
    public SegmentRef writeSegment(
            String repoId, String repoRevision, String indexedAt, EmbeddingProfile profile, IndexSchema schema,
            List<ChunkRecord> chunks, float[][] dense, List<String> roots) {
        if (!REPO_ID.matcher(repoId).matches()) {
            throw new CodeSearchException("invalid_request", "invalid repo_id");
        }
        int n = chunks.size();
        int dim = profile.dim();
        if (dense.length != n) {
            throw new CodeSearchException("invalid_request", "dense matrix does not match chunks and profile dim");
        }
        float[] flat = new float[n * dim];
        for (int r = 0; r < n; r++) {
            if (dense[r].length != dim) {
                throw new CodeSearchException("invalid_request", "dense matrix does not match chunks and profile dim");
            }
            double sum = 0;
            for (float x : dense[r]) {
                sum += (double) x * x;
            }
            double norm = Math.sqrt(sum);
            if (!Double.isFinite(norm) || norm < 1e-6) {
                throw new CodeSearchException("embedder_invalid_output", "null or non-finite vector in segment");
            }
            for (int j = 0; j < dim; j++) {
                flat[r * dim + j] = (float) (dense[r][j] / norm);
            }
        }

        Set<String> ids = new HashSet<>();
        byte[] idBytes = new byte[n * 16];
        for (int r = 0; r < n; r++) {
            String id = chunks.get(r).chunkId();
            if (!ids.add(id)) {
                throw new CodeSearchException("invalid_request", "duplicate chunk ids in segment");
            }
            try {
                UUID u = UUID.fromString(id);
                java.nio.ByteBuffer.wrap(idBytes, r * 16, 16).putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits());
            } catch (IllegalArgumentException e) {
                throw new CodeSearchException("invalid_request", "chunk id is not a UUID");
            }
        }

        List<String> sources = new ArrayList<>(new TreeSet<>(chunks.stream().map(ChunkRecord::source).toList()));
        List<String> languages = new ArrayList<>(new TreeSet<>(chunks.stream().map(ChunkRecord::language).toList()));
        Map<String, Integer> srcMap = index(sources);
        Map<String, Integer> langMap = index(languages);

        TreeSet<String> vocabTerms = new TreeSet<>();
        chunks.forEach(c -> vocabTerms.addAll(c.terms().keySet()));
        Map<String, Integer> vocab = new LinkedHashMap<>();
        vocabTerms.forEach(t -> vocab.put(t, vocab.size()));
        List<List<int[]>> perTerm = new ArrayList<>();
        vocabTerms.forEach(t -> perTerm.add(new ArrayList<>()));
        int[] docLen = new int[n];
        for (int doc = 0; doc < n; doc++) {
            for (Map.Entry<String, Integer> e : chunks.get(doc).terms().entrySet()) {
                docLen[doc] += e.getValue();
                perTerm.get(vocab.get(e.getKey())).add(new int[] {doc, e.getValue()});
            }
        }
        long[] postOffsets = new long[vocab.size() + 1];
        int[] df = new int[vocab.size()];
        int total = perTerm.stream().mapToInt(List::size).sum();
        int[] postDoc = new int[total];
        float[] postTf = new float[total];
        int p = 0;
        for (int t = 0; t < perTerm.size(); t++) {
            df[t] = perTerm.get(t).size();
            postOffsets[t + 1] = postOffsets[t] + df[t];
            for (int[] pair : perTerm.get(t)) {
                postDoc[p] = pair[0];
                postTf[p++] = pair[1];
            }
        }

        Path tmpDir = root.resolve("tmp").resolve("seg-" + UUID.randomUUID().toString().replace("-", ""));
        try {
            Files.createDirectory(tmpDir);
            ByteArrayOutputStream lines = new ByteArrayOutputStream();
            long[] offsets = new long[n + 1];
            for (int i = 0; i < n; i++) {
                ChunkRecord c = chunks.get(i);
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("id", c.chunkId());
                line.put("symbol", c.symbol());
                line.put("parent_symbol", c.parentSymbol());
                line.put("text", c.text());
                lines.writeBytes(JSON.writeValueAsBytes(line));
                lines.write('\n');
                offsets[i + 1] = lines.size();
            }
            FileSupport.atomicWrite(tmpDir.resolve("chunks.jsonl"), lines.toByteArray());
            FileSupport.atomicWrite(tmpDir.resolve("ids.bin"), idBytes);
            BinIo.write(tmpDir.resolve("dense.bin"), flat);
            BinIo.write(tmpDir.resolve("source_idx.bin"), chunks.stream().mapToInt(c -> srcMap.get(c.source())).toArray());
            BinIo.write(tmpDir.resolve("lang_idx.bin"), chunks.stream().mapToInt(c -> langMap.get(c.language())).toArray());
            BinIo.write(tmpDir.resolve("start_line.bin"), chunks.stream().mapToInt(ChunkRecord::startLine).toArray());
            BinIo.write(tmpDir.resolve("end_line.bin"), chunks.stream().mapToInt(ChunkRecord::endLine).toArray());
            BinIo.write(tmpDir.resolve("chunks.offsets.bin"), offsets);
            BinIo.write(tmpDir.resolve("df.bin"), df);
            BinIo.write(tmpDir.resolve("postings_offsets.bin"), postOffsets);
            BinIo.write(tmpDir.resolve("postings_doc.bin"), postDoc);
            BinIo.write(tmpDir.resolve("postings_tf.bin"), postTf);
            BinIo.write(tmpDir.resolve("doc_len.bin"), docLen);
            FileSupport.atomicWrite(tmpDir.resolve("sources.json"), JSON.writeValueAsBytes(sources));
            FileSupport.atomicWrite(tmpDir.resolve("languages.json"), JSON.writeValueAsBytes(languages));
            FileSupport.atomicWrite(tmpDir.resolve("vocab.json"), JSON.writeValueAsBytes(vocab));

            Map<String, Object> files = new TreeMap<>();
            for (String name : SEGMENT_FILES) {
                Map<String, Object> meta = new TreeMap<>();
                meta.put("sha256", sha256(tmpDir.resolve(name)));
                meta.put("bytes", Files.size(tmpDir.resolve(name)));
                files.put(name, meta);
            }
            double avgdl = n == 0 ? 0.0 : (double) java.util.Arrays.stream(docLen).asLongStream().sum() / n;
            Map<String, Object> manifest = new TreeMap<>();
            manifest.put("repo_id", repoId);
            manifest.put("repo_revision", repoRevision);
            manifest.put("roots", List.copyOf(roots));
            manifest.put("indexed_at", indexedAt);
            manifest.put("profile_id", profile.profileId());
            manifest.put("schema_id", schema.schemaId());
            manifest.put("counts", Map.of("chunks", n, "sources", sources.size(), "terms", vocab.size(), "dim", dim));
            manifest.put("lexical", Map.of("k1", schema.bm25K1(), "b", schema.bm25B(), "avgdl", avgdl));
            manifest.put("files", files);
            String segmentId = repoId + "-" + sha256(SORTED.writeValueAsBytes(manifest)).substring(0, 16);
            manifest.put("segment_id", segmentId);
            byte[] manifestBytes = SORTED.writeValueAsBytes(manifest);
            FileSupport.atomicWrite(tmpDir.resolve("manifest.json"), manifestBytes);
            SegmentRef ref = new SegmentRef(repoId, segmentId, sha256(manifestBytes));

            verifySegment(tmpDir, ref, true);
            Path dest = root.resolve("segments").resolve(segmentId);
            if (Files.exists(dest)) {
                deleteTree(tmpDir);
            } else {
                Files.move(tmpDir, dest);
            }
            return ref;
        } catch (IOException e) {
            throw unavailable("could not write segment");
        } finally {
            deleteTree(tmpDir);
        }
    }

    private static Map<String, Integer> index(List<String> values) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < values.size(); i++) {
            map.put(values.get(i), i);
        }
        return map;
    }

    @Override
    public String publish(EmbeddingProfile profile, IndexSchema schema, List<SegmentRef> replace, List<String> remove) {
        try (FileLock ignored = writeLock()) {
            Optional<String[]> current = readPointer("active.json");
            Map<String, SegmentRef> carried = new TreeMap<>();
            if (current.isPresent()) {
                for (JsonNode s : readGeneration(current.get()[0], null).get("segments")) {
                    SegmentRef ref = refOf(s);
                    carried.put(ref.repoId(), ref);
                }
            }
            remove.forEach(carried::remove);
            Map<String, SegmentRef> fresh = new HashMap<>();
            replace.forEach(ref -> fresh.put(ref.repoId(), ref));
            carried.putAll(fresh);

            for (Map.Entry<String, SegmentRef> e : carried.entrySet()) {
                JsonNode manifest;
                try {
                    manifest = verifySegment(root.resolve("segments").resolve(e.getValue().segmentId()), e.getValue(), fresh.containsKey(e.getKey()));
                } catch (Missing m) {
                    throw unavailable("segment is missing");
                }
                if (!manifest.get("profile_id").asString().equals(profile.profileId())
                        || !manifest.get("schema_id").asString().equals(schema.schemaId())) {
                    throw new CodeSearchException("profile_mismatch", "segment was built with a different profile or schema");
                }
            }

            Instant now = Instant.now();
            Map<String, Object> body = new TreeMap<>();
            String genId = COMPACT.format(now) + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            body.put("generation_id", genId);
            body.put("created_at", ISO.format(now));
            body.put("embedding_profile", profileMap(profile));
            body.put("index_schema", schema.toMap());
            body.put("segments", carried.values().stream().map(r -> {
                Map<String, Object> m = new TreeMap<>();
                m.put("repo_id", r.repoId());
                m.put("segment_id", r.segmentId());
                m.put("manifest_sha256", r.manifestSha256());
                return m;
            }).toList());
            byte[] genBytes = SORTED.writeValueAsBytes(body);
            FileSupport.atomicWrite(root.resolve("generations").resolve(genId + ".json"), genBytes);

            byte[] oldActive = current.isPresent() ? readBytes("active.json") : null;
            writePointer("active.json", genId, sha256(genBytes)); // commit
            if (oldActive != null) {
                FileSupport.atomicWrite(root.resolve("previous.json"), oldActive);
            }
            return genId;
        }
    }

    @Override
    public String rollback() {
        try (FileLock ignored = writeLock()) {
            String[] prev = readPointer("previous.json").orElseThrow(() -> unavailable("no previous generation to roll back to"));
            readGeneration(prev[0], null);
            byte[] oldActive = Files.exists(root.resolve("active.json")) ? readBytes("active.json") : null;
            FileSupport.atomicWrite(root.resolve("active.json"), readBytes("previous.json"));
            if (oldActive != null) {
                FileSupport.atomicWrite(root.resolve("previous.json"), oldActive);
            }
            return prev[0];
        }
    }

    @Override
    public Map<String, Integer> prune(double tmpMaxAgeSeconds) {
        Map<String, Integer> removed = new LinkedHashMap<>(Map.of("generations", 0, "segments", 0, "skipped", 0));
        try (FileLock ignored = writeLock()) {
            Set<String> keep = new HashSet<>();
            for (String name : List.of("active.json", "previous.json")) {
                readPointer(name).ifPresent(ptr -> keep.add(ptr[0]));
            }
            Set<String> referenced = new HashSet<>();
            for (Path gen : list(root.resolve("generations"))) {
                String stem = gen.getFileName().toString().replaceFirst("\\.json$", "");
                if (keep.contains(stem)) {
                    for (JsonNode s : readJson(gen).get("segments")) {
                        referenced.add(s.get("segment_id").asString());
                    }
                } else {
                    Files.deleteIfExists(gen);
                    removed.merge("generations", 1, Integer::sum);
                }
            }
            for (Path seg : list(root.resolve("segments"))) {
                if (!referenced.contains(seg.getFileName().toString())) {
                    removed.merge(deleteTree(seg) ? "segments" : "skipped", 1, Integer::sum);
                }
            }
            long now = System.currentTimeMillis();
            for (Path leftover : list(root.resolve("tmp"))) {
                if (now - Files.getLastModifiedTime(leftover).toMillis() > tmpMaxAgeSeconds * 1000) {
                    deleteTree(leftover);
                }
            }
        } catch (IOException e) {
            throw unavailable("prune failed");
        }
        return removed;
    }

    // ------------------------------------------------------------------ leitura

    public Optional<String> activeGenerationId() {
        return readPointer("active.json").map(ptr -> ptr[0]);
    }

    @Override
    public Optional<String> previousGenerationId() {
        return readPointer("previous.json").map(ptr -> ptr[0]);
    }

    @Override
    public Optional<IndexSnapshot> openActive() {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return openActiveOnce();
            } catch (Missing m) {
                // publicação + prune concorrentes; relê o ponteiro
            }
        }
        throw unavailable("vector store unavailable");
    }

    private Optional<IndexSnapshot> openActiveOnce() {
        Optional<String[]> ptr = readPointer("active.json");
        if (ptr.isEmpty()) {
            return Optional.empty();
        }
        JsonNode gen = readGeneration(ptr.get()[0], ptr.get()[1]);
        Map<String, StoredSegment> segments = new HashMap<>();
        Set<String> live = new HashSet<>();
        for (JsonNode entry : gen.get("segments")) {
            SegmentRef ref = refOf(entry);
            Path dir = root.resolve("segments").resolve(ref.segmentId());
            verifySegment(dir, ref, false);
            String key = ref.segmentId() + ":" + ref.manifestSha256();
            live.add(key);
            StoredSegment seg = loaded.get(key);
            if (seg == null) {
                try {
                    seg = new StoredSegment(dir, ref);
                } catch (NoSuchFileException e) {
                    throw new Missing();
                } catch (IOException e) {
                    throw unavailable("segment is unreadable");
                }
                loaded.put(key, seg);
            }
            segments.put(ref.repoId(), seg);
        }
        loaded.keySet().removeIf(k -> !live.contains(k));
        JsonNode p = gen.get("embedding_profile");
        EmbeddingProfile profile = new EmbeddingProfile(p.get("model").asString(), p.get("digest").asString(), p.get("dim").asInt(),
                p.get("doc_prefix").asString(), p.get("query_prefix").asString(), p.get("max_input_tokens").asInt());
        JsonNode s = gen.get("index_schema");
        IndexSchema schema = new IndexSchema(s.get("chunker_version").asString(), s.get("tokenizer_version").asString(),
                s.get("policy_version").asString(), s.get("bm25_k1").asDouble(), s.get("bm25_b").asDouble(),
                s.has("describer_id") ? s.get("describer_id").asString() : "");
        return Optional.of(new FileSnapshot(gen.get("generation_id").asString(), gen.get("created_at").asString(), profile, schema, segments));
    }

    // ---------------------------------------------------------------- internos

    private static Map<String, Object> profileMap(EmbeddingProfile p) {
        Map<String, Object> m = new TreeMap<>();
        m.put("model", p.model());
        m.put("digest", p.digest());
        m.put("dim", p.dim());
        m.put("metric", "cosine");
        m.put("normalized", true);
        m.put("doc_prefix", p.docPrefix());
        m.put("query_prefix", p.queryPrefix());
        m.put("max_input_tokens", p.maxInputTokens());
        return m;
    }

    private static SegmentRef refOf(JsonNode s) {
        return new SegmentRef(s.get("repo_id").asString(), s.get("segment_id").asString(), s.get("manifest_sha256").asString());
    }

    private byte[] readBytes(String name) {
        try {
            return Files.readAllBytes(root.resolve(name));
        } catch (IOException e) {
            throw unavailable(name + " is unreadable");
        }
    }

    private static JsonNode readJson(Path file) throws IOException {
        return JSON.readTree(Files.readAllBytes(file));
    }

    private void writePointer(String name, String generation, String sha) {
        Map<String, Object> pointer = new TreeMap<>();
        pointer.put("generation", generation);
        pointer.put("sha256", sha);
        FileSupport.atomicWrite(root.resolve(name), JSON.writeValueAsBytes(pointer));
    }

    private Optional<String[]> readPointer(String name) {
        byte[] raw;
        try {
            raw = Files.readAllBytes(root.resolve(name));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw unavailable(name + " is unreadable");
        }
        try {
            JsonNode data = JSON.readTree(raw);
            return Optional.of(new String[] {data.get("generation").asString(), data.get("sha256").asString()});
        } catch (RuntimeException e) {
            throw unavailable(name + " is corrupt");
        }
    }

    private JsonNode readGeneration(String genId, String expectedSha) {
        if (!GENERATION_ID.matcher(genId).matches()) {
            throw unavailable("invalid generation id");
        }
        byte[] raw;
        try {
            raw = Files.readAllBytes(root.resolve("generations").resolve(genId + ".json"));
        } catch (NoSuchFileException e) {
            throw new Missing();
        } catch (IOException e) {
            throw unavailable("generation is unreadable");
        }
        if (expectedSha != null && !sha256(raw).equals(expectedSha)) {
            throw unavailable("generation file does not match its pointer");
        }
        return JSON.readTree(raw);
    }

    private JsonNode verifySegment(Path dir, SegmentRef ref, boolean full) {
        byte[] raw;
        try {
            raw = Files.readAllBytes(dir.resolve("manifest.json"));
        } catch (NoSuchFileException e) {
            if (full) {
                throw unavailable("segment is missing");
            }
            throw new Missing();
        } catch (IOException e) {
            throw unavailable("segment manifest is unreadable");
        }
        if (!sha256(raw).equals(ref.manifestSha256())) {
            throw unavailable("segment manifest hash mismatch");
        }
        JsonNode manifest = JSON.readTree(raw);
        try {
            for (var file : manifest.get("files").properties()) {
                Path path = dir.resolve(file.getKey());
                if (Files.size(path) != file.getValue().get("bytes").asLong()
                        || (full && !sha256(path).equals(file.getValue().get("sha256").asString()))) {
                    throw unavailable("segment file integrity check failed");
                }
            }
            JsonNode counts = manifest.get("counts");
            if (Files.size(dir.resolve("dense.bin")) != (long) counts.get("chunks").asInt() * counts.get("dim").asInt() * Float.BYTES) {
                throw unavailable("segment chunk count mismatch");
            }
        } catch (NoSuchFileException e) {
            throw new Missing();
        } catch (IOException e) {
            throw unavailable("segment file integrity check failed");
        }
        return manifest;
    }

    private static List<Path> list(Path dir) throws IOException {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            stream.forEach(out::add);
        }
        return out;
    }

    /** Remove o diretório ou arquivo; false se algo não pôde ser apagado (no Windows, arquivo ainda aberto). */
    private static boolean deleteTree(Path path) {
        if (!Files.exists(path)) {
            return true;
        }
        try (var walk = Files.walk(path)) {
            boolean ok = true;
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    ok = false;
                }
            }
            return ok;
        } catch (IOException e) {
            return false;
        }
    }
}
