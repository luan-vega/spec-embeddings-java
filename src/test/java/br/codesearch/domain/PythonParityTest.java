package br.codesearch.domain;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Paridade com a versão Python: os fixtures de {@code golden.json} são gerados por {@code scripts/generate_golden.py}
 * a partir do código Python, que é a fonte da verdade. Se o Python mudar, os fixtures são regenerados.
 */
class PythonParityTest {

    private static final Path GOLDEN = Path.of("src/test/resources/golden");
    private static JsonNode golden;

    @BeforeAll
    static void load() throws IOException {
        golden = new ObjectMapper().readTree(GOLDEN.resolve("golden.json").toFile());
    }

    @Test
    void chunkerVersionMatches() {
        assertThat(JavaChunker.CHUNKER_VERSION).isEqualTo(golden.get("chunker_version").asString());
    }

    @Test
    void chunksMatchPythonForEverySampleFile() throws IOException {
        var files = golden.get("chunks").properties().iterator();
        int compared = 0;
        while (files.hasNext()) {
            var entry = files.next();
            String name = entry.getKey();
            String text = new String(Files.readAllBytes(GOLDEN.resolve("java").resolve(name)), StandardCharsets.UTF_8);
            List<ChunkDraft> actual = JavaChunker.chunkJava("src/main/java/br/" + name, text);
            JsonNode expected = entry.getValue();
            assertThat(actual).as(name + " chunk count").hasSize(expected.size());
            for (int i = 0; i < actual.size(); i++) {
                ChunkDraft d = actual.get(i);
                JsonNode e = expected.get(i);
                String where = name + "#" + i + " (" + e.get("symbol").asString() + ")";
                assertThat(d.source()).as(where + " source").isEqualTo(e.get("source").asString());
                assertThat(d.symbol()).as(where + " symbol").isEqualTo(e.get("symbol").asString());
                assertThat(d.parentSymbol()).as(where + " parent").isEqualTo(e.get("parent_symbol").asString());
                assertThat(d.className()).as(where + " class").isEqualTo(e.get("class_name").asString());
                assertThat(d.startLine()).as(where + " start").isEqualTo(e.get("start_line").asInt());
                assertThat(d.endLine()).as(where + " end").isEqualTo(e.get("end_line").asInt());
                assertThat(d.text()).as(where + " text").isEqualTo(e.get("text").asString());
                assertThat(d.embedKey("svc")).as(where + " key").isEqualTo(e.get("embed_key").asString());
                assertThat(d.embedKey("svc", "desc-1")).as(where + " key+d").isEqualTo(e.get("embed_key_described").asString());
                assertThat(d.contentHash()).as(where + " hash").isEqualTo(e.get("content_hash").asString());
                assertThat(d.embedText("svc")).as(where + " embed text").isEqualTo(e.get("embed_text").asString());
                assertThat(d.embedText("svc", "Does a thing.")).as(where + " embed text+d")
                        .isEqualTo(e.get("embed_text_described").asString());
                assertThat(ChunkIds.makeChunkId("p1", "svc", d.source(), d.symbol(), d.contentHash(), d.startLine()))
                        .as(where + " chunk id").isEqualTo(e.get("chunk_id").asString());
                compared++;
            }
        }
        assertThat(compared).isGreaterThan(20);
    }

    @Test
    void tokenizerAndSparseEncodingMatch() {
        for (JsonNode c : golden.get("sparse")) {
            String text = c.get("text").asString();
            List<String> terms = new ArrayList<>();
            c.get("terms").forEach(t -> terms.add(t.asString()));
            assertThat(SparseTokenizer.tokenize(text)).as(text).isEqualTo(terms);

            Map<String, Integer> doc = new LinkedHashMap<>();
            c.get("doc").properties().forEach(f -> doc.put(f.getKey(), f.getValue().asInt()));
            assertThat(SparseTokenizer.encodeDocument(text)).as(text + " doc").isEqualTo(doc);

            Map<String, Double> query = new LinkedHashMap<>();
            c.get("query").properties().forEach(f -> query.put(f.getKey(), f.getValue().asDouble()));
            assertThat(SparseTokenizer.encodeQuery(text)).as(text + " query").isEqualTo(query);
        }
    }

    @Test
    void rrfMatchesPythonIncludingScores() {
        for (JsonNode c : golden.get("fusion").get("rrf")) {
            List<List<Hit>> rankings = new ArrayList<>();
            for (JsonNode r : c.get("rankings")) {
                List<Hit> ranking = new ArrayList<>();
                r.forEach(h -> ranking.add(new Hit(h.get(0).asString(), h.get(1).asInt(), 0.0)));
                rankings.add(ranking);
            }
            List<Hit> fused = Fusion.rrf(rankings, c.get("k").asInt());
            JsonNode expected = c.get("fused");
            assertThat(fused).hasSize(expected.size());
            for (int i = 0; i < fused.size(); i++) {
                assertThat(fused.get(i).repoId()).isEqualTo(expected.get(i).get(0).asString());
                assertThat(fused.get(i).row()).isEqualTo(expected.get(i).get(1).asInt());
                assertThat(fused.get(i).score()).isEqualTo(expected.get(i).get(2).asDouble());
            }
        }
    }

    @Test
    void collapseOverlapsMatchesPython() {
        Map<String, Location> locations = new HashMap<>();
        for (JsonNode l : golden.get("fusion").get("locations")) {
            locations.put(l.get(0).asString() + "/" + l.get(1).asInt(),
                    new Location(l.get(2).asString(), l.get(3).asInt(), l.get(4).asInt()));
        }
        List<Hit> order = List.of(new Hit("a", 1, 0), new Hit("a", 2, 0), new Hit("b", 1, 0), new Hit("a", 3, 0), new Hit("b", 2, 0));
        for (JsonNode c : golden.get("fusion").get("collapse")) {
            List<Hit> kept = Fusion.collapseOverlaps(order, h -> locations.get(h.repoId() + "/" + h.row()), c.get("limit").asInt());
            List<String> actual = kept.stream().map(h -> h.repoId() + "/" + h.row()).toList();
            List<String> expected = new ArrayList<>();
            c.get("kept").forEach(h -> expected.add(h.get(0).asString() + "/" + h.get(1).asInt()));
            assertThat(actual).isEqualTo(expected);
        }
    }

    @Test
    void policyMatchesPython() throws IOException {
        JsonNode p = golden.get("policy");
        ExclusionPolicy policy = ExclusionPolicy.fromDict(ExclusionPolicy.DEFAULT_POLICY);
        assertThat(policy.valid()).isTrue();
        assertThat(policy.versionId()).isEqualTo(p.get("default_version_id").asString());
        for (JsonNode path : p.get("paths")) {
            assertThat(policy.allows(path.get(0).asString())).as("allows(" + path.get(0).asString() + ")")
                    .isEqualTo(path.get(1).asBoolean());
        }
        for (JsonNode secret : p.get("secrets")) {
            assertThat(policy.containsSecret(secret.get(0).asString())).as("secret(" + secret.get(0).asString() + ")")
                    .isEqualTo(secret.get(1).asBoolean());
        }
        Map<String, Object> noExt = new HashMap<>(ExclusionPolicy.DEFAULT_POLICY);
        noExt.put("include_extensions", List.of());
        assertThat(ExclusionPolicy.fromDict(noExt).valid()).isEqualTo(p.get("invalid_empty_ext").asBoolean());
        assertThat(ExclusionPolicy.fromDict(Map.of("version", "1")).valid()).isEqualTo(p.get("invalid_missing_key").asBoolean());
        assertThat(ExclusionPolicy.denyAll("x").allows("A.java")).isFalse();
    }

    @Test
    void canonicalJsonMatchesPython() {
        JsonNode p = golden.get("policy");
        assertThat(CanonicalJson.write(ExclusionPolicy.DEFAULT_POLICY)).isEqualTo(p.get("canonical").get(0).asString());
        Map<String, Object> small = new HashMap<>();
        small.put("b", 1);
        small.put("a", List.of("x", "ç\n\"\\"));
        assertThat(CanonicalJson.write(small)).isEqualTo(p.get("canonical").get(1).asString());
        Map<String, Object> digest = new HashMap<>();
        digest.put("b", 1);
        digest.put("a", List.of("x", "ç"));
        assertThat(CanonicalJson.shortDigest(digest)).isEqualTo(p.get("short_digest").asString());
    }
}
