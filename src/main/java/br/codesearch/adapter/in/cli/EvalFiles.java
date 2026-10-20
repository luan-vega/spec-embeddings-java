package br.codesearch.adapter.in.cli;

import br.codesearch.application.Evaluation;
import br.codesearch.domain.CodeSearchException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Leitura do dataset (JSONL: {@code {"id","uc","query","relevant":[{"repo","source","symbol"?}]}}) e dos limiares do gate,
 * no mesmo formato da versão Python, para as duas rodarem exatamente os mesmos arquivos.
 */
final class EvalFiles {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private EvalFiles() {}

    static List<Evaluation.Case> loadDataset(Path path) {
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CodeSearchException("invalid_request", "cannot read the dataset file");
        }
        List<Evaluation.Case> cases = new ArrayList<>();
        for (int n = 1; n <= lines.size(); n++) {
            if (lines.get(n - 1).isBlank()) {
                continue;
            }
            try {
                JsonNode raw = JSON.readTree(lines.get(n - 1));
                List<Evaluation.Relevant> relevant = new ArrayList<>();
                for (JsonNode item : required(raw, "relevant")) {
                    relevant.add(new Evaluation.Relevant(text(item, "repo"), text(item, "source"),
                            item.has("symbol") ? text(item, "symbol") : null));
                }
                cases.add(new Evaluation.Case(text(raw, "id"), text(raw, "uc"), text(raw, "query"), relevant));
            } catch (JacksonException | IllegalArgumentException e) {
                throw new CodeSearchException("invalid_request", "invalid dataset line " + n);
            }
        }
        return cases;
    }

    static Evaluation.Thresholds loadThresholds(Path path) {
        byte[] raw;
        try {
            raw = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new CodeSearchException("invalid_request", "cannot read the thresholds file");
        }
        try {
            JsonNode root = JSON.readTree(raw);
            int k = root.has("k") ? root.get("k").asInt() : 10;
            List<Evaluation.Rule> rules = new ArrayList<>();
            for (JsonNode r : required(root, "rules")) {
                Map<String, Object> asMap = new LinkedHashMap<>();
                r.properties().forEach(e -> asMap.put(e.getKey(), JSON.convertValue(e.getValue(), Object.class)));
                Double delta = r.has("delta") ? r.get("delta").doubleValue() : null;
                String vs = r.has("min_delta_vs") ? text(r, "min_delta_vs") : null;
                if (vs != null && delta == null) {
                    throw new IllegalArgumentException("delta");
                }
                rules.add(new Evaluation.Rule(text(r, "uc"), text(r, "mode"), text(r, "metric"),
                        r.has("min") ? r.get("min").doubleValue() : null, r.has("max") ? r.get("max").doubleValue() : null,
                        vs, delta, asMap));
            }
            return new Evaluation.Thresholds(k, rules, sha256(raw));
        } catch (JacksonException | IllegalArgumentException e) {
            throw new CodeSearchException("invalid_request", "invalid thresholds file");
        }
    }

    private static JsonNode required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            throw new IllegalArgumentException(field);
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString()) {
            throw new IllegalArgumentException(field);
        }
        return value.asString();
    }

    private static String sha256(byte[] raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
