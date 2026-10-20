package br.codesearch.adapter.out.file;

import br.codesearch.domain.ChunkView;
import br.codesearch.domain.CodeSearchException;
import br.codesearch.domain.Filters;
import br.codesearch.domain.Hit;
import br.codesearch.domain.Location;
import br.codesearch.domain.Paths;
import br.codesearch.domain.SegmentInfo;
import br.codesearch.domain.SegmentRef;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Segmento imutável carregado em memória. Busca densa (produto escalar) e léxica (BM25) sobre os arrays. */
final class StoredSegment {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    final SegmentRef ref;
    final int count;
    final JsonNode manifest;
    private final Path dir;
    private final int dim;
    private final float[] dense;
    private final int[] sourceIdx;
    private final int[] langIdx;
    private final int[] startLine;
    private final int[] endLine;
    private final long[] offsets;
    private final int[] df;
    private final long[] postOffsets;
    private final int[] postDoc;
    private final float[] postTf;
    private final int[] docLen;
    private final List<String> sources;
    private final List<String> languages;
    private final Map<String, Integer> vocab;
    private final double k1;
    private final double b;
    private final double avgdl;

    @SuppressWarnings("unchecked")
    StoredSegment(Path dir, SegmentRef ref) throws IOException {
        this.dir = dir;
        this.ref = ref;
        this.manifest = JSON.readTree(Files.readAllBytes(dir.resolve("manifest.json")));
        this.dim = manifest.get("counts").get("dim").asInt();
        this.count = manifest.get("counts").get("chunks").asInt();
        this.dense = BinIo.floats(dir.resolve("dense.bin"));
        this.sourceIdx = BinIo.ints(dir.resolve("source_idx.bin"));
        this.langIdx = BinIo.ints(dir.resolve("lang_idx.bin"));
        this.startLine = BinIo.ints(dir.resolve("start_line.bin"));
        this.endLine = BinIo.ints(dir.resolve("end_line.bin"));
        this.offsets = BinIo.longs(dir.resolve("chunks.offsets.bin"));
        this.df = BinIo.ints(dir.resolve("df.bin"));
        this.postOffsets = BinIo.longs(dir.resolve("postings_offsets.bin"));
        this.postDoc = BinIo.ints(dir.resolve("postings_doc.bin"));
        this.postTf = BinIo.floats(dir.resolve("postings_tf.bin"));
        this.docLen = BinIo.ints(dir.resolve("doc_len.bin"));
        this.sources = JSON.readValue(Files.readAllBytes(dir.resolve("sources.json")), List.class);
        this.languages = JSON.readValue(Files.readAllBytes(dir.resolve("languages.json")), List.class);
        this.vocab = JSON.readValue(Files.readAllBytes(dir.resolve("vocab.json")), Map.class);
        JsonNode lex = manifest.get("lexical");
        this.k1 = lex.get("k1").asDouble();
        this.b = lex.get("b").asDouble();
        double avg = lex.get("avgdl").asDouble();
        this.avgdl = avg == 0.0 ? 1.0 : avg;
        if (dense.length != (long) count * dim || sourceIdx.length != count || offsets.length != count + 1) {
            throw new CodeSearchException("vector_store_unavailable", "segment arrays are inconsistent");
        }
    }

    SegmentInfo info() {
        List<String> roots = new ArrayList<>();
        manifest.path("roots").forEach(r -> roots.add(r.asString()));
        return new SegmentInfo(ref.repoId(), manifest.get("repo_revision").asString(), manifest.get("indexed_at").asString(),
                List.copyOf(roots), manifest.get("profile_id").asString(), manifest.get("schema_id").asString(), count);
    }

    /** Máscara de linhas que passam nos filtros, ou nulo quando nenhum filtro se aplica. */
    boolean[] rowMask(Filters filters) {
        boolean[] mask = null;
        if (filters.languages() != null) {
            Set<Integer> ids = new HashSet<>();
            for (int i = 0; i < languages.size(); i++) {
                if (filters.languages().contains(languages.get(i))) {
                    ids.add(i);
                }
            }
            mask = new boolean[count];
            for (int r = 0; r < count; r++) {
                mask[r] = ids.contains(langIdx[r]);
            }
        }
        if (!filters.pathPrefix().isEmpty()) {
            List<String> prefix = filters.pathPrefix();
            Set<Integer> ids = new HashSet<>();
            for (int i = 0; i < sources.size(); i++) {
                List<String> parts = Paths.components(sources.get(i));
                if (parts.size() >= prefix.size() && parts.subList(0, prefix.size()).equals(prefix)) {
                    ids.add(i);
                }
            }
            boolean[] byPath = new boolean[count];
            for (int r = 0; r < count; r++) {
                byPath[r] = ids.contains(sourceIdx[r]) && (mask == null || mask[r]);
            }
            mask = byPath;
        }
        return mask;
    }

    List<Hit> searchDense(float[] q, int depth, boolean[] mask) {
        double[] scores = new double[count];
        int[] rows = new int[count];
        int n = 0;
        for (int r = 0; r < count; r++) {
            if (mask != null && !mask[r]) {
                continue;
            }
            double dot = 0;
            int base = r * dim;
            for (int j = 0; j < dim; j++) {
                dot += (double) dense[base + j] * q[j];
            }
            rows[n] = r;
            scores[n++] = dot;
        }
        return top(rows, scores, n, depth);
    }

    List<Hit> searchLexical(Collection<String> terms, int depth, boolean[] mask) {
        if (count == 0) {
            return List.of();
        }
        double[] scores = new double[count];
        double[] dlNorm = new double[count];
        for (int i = 0; i < count; i++) {
            dlNorm[i] = k1 * (1.0 - b + b * (docLen[i] / avgdl));
        }
        for (String term : new HashSet<>(terms)) {
            Integer tid = vocab.get(term);
            if (tid == null) {
                continue;
            }
            int lo = (int) postOffsets[tid];
            int hi = (int) postOffsets[tid + 1];
            double dfv = df[tid];
            double idf = Math.log(1.0 + (count - dfv + 0.5) / (dfv + 0.5));
            for (int p = lo; p < hi; p++) {
                int doc = postDoc[p];
                double tf = postTf[p];
                scores[doc] += idf * tf * (k1 + 1.0) / (tf + dlNorm[doc]);
            }
        }
        int[] rows = new int[count];
        double[] kept = new double[count];
        int n = 0;
        for (int r = 0; r < count; r++) {
            if (scores[r] > 0 && (mask == null || mask[r])) {
                rows[n] = r;
                kept[n++] = scores[r];
            }
        }
        return top(rows, kept, n, depth);
    }

    /** Os {@code depth} maiores scores, do maior para o menor; empate pelo menor índice de linha. */
    private List<Hit> top(int[] rows, double[] scores, int n, int depth) {
        if (n == 0 || depth <= 0) {
            return List.of();
        }
        // fila com o pior candidato na frente: menor score, e em empate a linha maior
        PriorityQueue<Integer> worstFirst = new PriorityQueue<>((x, y) -> {
            int c = Double.compare(scores[x], scores[y]);
            return c != 0 ? c : Integer.compare(rows[y], rows[x]);
        });
        for (int i = 0; i < n; i++) {
            worstFirst.add(i);
            if (worstFirst.size() > depth) {
                worstFirst.poll();
            }
        }
        List<Integer> picked = new ArrayList<>(worstFirst);
        picked.sort((x, y) -> {
            int c = Double.compare(scores[y], scores[x]);
            return c != 0 ? c : Integer.compare(rows[x], rows[y]);
        });
        List<Hit> hits = new ArrayList<>();
        for (int i : picked) {
            hits.add(new Hit(ref.repoId(), rows[i], scores[i]));
        }
        return hits;
    }

    Location locate(int row) {
        return new Location(sources.get(sourceIdx[row]), startLine[row], endLine[row]);
    }

    ChunkView record(int row) {
        if (row < 0 || row >= count) {
            throw new IndexOutOfBoundsException(row);
        }
        try (RandomAccessFile file = new RandomAccessFile(dir.resolve("chunks.jsonl").toFile(), "r")) {
            byte[] raw = new byte[(int) (offsets[row + 1] - offsets[row])];
            file.seek(offsets[row]);
            file.readFully(raw);
            JsonNode json = JSON.readTree(new String(raw, StandardCharsets.UTF_8));
            return new ChunkView(ref.repoId(), sources.get(sourceIdx[row]), json.get("symbol").asString(),
                    json.get("parent_symbol").asString(), startLine[row], endLine[row], languages.get(langIdx[row]),
                    json.get("text").asString(), manifest.get("repo_revision").asString(), manifest.get("indexed_at").asString());
        } catch (IOException e) {
            throw new CodeSearchException("vector_store_unavailable", "could not read chunk");
        }
    }
}
