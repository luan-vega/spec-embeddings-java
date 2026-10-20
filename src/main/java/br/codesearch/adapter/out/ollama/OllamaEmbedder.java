package br.codesearch.adapter.out.ollama;

import br.codesearch.application.port.Embedder;
import br.codesearch.domain.CodeSearchException;
import br.codesearch.domain.EmbeddingProfile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** Embedder Ollama em loopback. Sem auto-pull, sem truncamento silencioso e sem vetores inválidos. */
public final class OllamaEmbedder implements Embedder {

    private static final int BATCH = 16;

    private final OllamaClient client;
    private final String model;
    private final EmbeddingProfile profile;

    public OllamaEmbedder(String model, OllamaClient client, String docPrefix, String queryPrefix) {
        if (model == null || model.isBlank()) {
            throw new CodeSearchException("embedder_unavailable", "CODE_SEARCH_OLLAMA_MODEL is required");
        }
        this.client = client;
        this.model = model;
        Probe probe = probe(client, model);
        this.profile = new EmbeddingProfile(model, probe.digest, probe.dim, docPrefix, queryPrefix, probe.maxTokens);
    }

    record Probe(String digest, int dim, int maxTokens) {}

    private static boolean modelMatches(String name, String wanted) {
        return name.equals(wanted) || (!wanted.contains(":") && name.equals(wanted + ":latest"));
    }

    /** Digest, dimensão e janela do modelo já instalado. Nunca baixa nada. */
    static Probe probe(OllamaClient client, String model) {
        OllamaClient.Response tags = client.request("GET", "/api/tags", null);
        if (tags.status() != 200 || tags.body() == null) {
            throw new CodeSearchException("embedder_unavailable", "Ollama did not list models");
        }
        String digest = null;
        for (JsonNode m : tags.body().path("models")) {
            if (modelMatches(m.path("name").asString(""), model)) {
                digest = m.path("digest").asString("");
                break;
            }
        }
        if (digest == null || digest.isEmpty()) {
            throw new CodeSearchException("embedder_unavailable", "model '" + model + "' is not installed in Ollama (pull it manually)");
        }
        int maxTokens = 0;
        OllamaClient.Response info = client.request("POST", "/api/show", Map.of("model", model));
        if (info.status() == 200 && info.body() != null) {
            for (var entry : info.body().path("model_info").properties()) {
                if (entry.getKey().endsWith(".context_length") && entry.getValue().isIntegralNumber()) {
                    maxTokens = entry.getValue().asInt();
                }
            }
        }
        List<float[]> vectors = embed(client, model, List.of("probe"));
        return new Probe(digest.startsWith("sha256:") ? digest : "sha256:" + digest, vectors.get(0).length, maxTokens);
    }

    private static List<float[]> embed(OllamaClient client, String model, List<String> texts) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", texts);
        body.put("truncate", false);
        OllamaClient.Response resp = client.request("POST", "/api/embed", body);
        if (resp.status() == 400) {
            throw new CodeSearchException("embedder_invalid_output", "input rejected by the model (too long?)");
        }
        if (resp.status() != 200 || resp.body() == null) {
            throw new CodeSearchException("embedder_unavailable", "Ollama failed to embed");
        }
        JsonNode rows = resp.body().path("embeddings");
        if (!rows.isArray() || rows.size() != texts.size()) {
            throw new CodeSearchException("embedder_invalid_output", "unexpected embeddings shape");
        }
        List<float[]> out = new ArrayList<>();
        int dim = -1;
        for (JsonNode row : rows) {
            if (!row.isArray() || row.isEmpty() || (dim >= 0 && row.size() != dim)) {
                throw new CodeSearchException("embedder_invalid_output", "unexpected embeddings shape");
            }
            dim = row.size();
            float[] v = new float[dim];
            for (int i = 0; i < dim; i++) {
                if (!row.get(i).isNumber()) {
                    throw new CodeSearchException("embedder_invalid_output", "malformed embeddings");
                }
                v[i] = row.get(i).floatValue();
            }
            out.add(v);
        }
        return out;
    }

    public boolean ready() {
        try {
            return client.request("GET", "/api/tags", null).status() == 200;
        } catch (CodeSearchException e) {
            return false;
        }
    }

    private float[] checked(float[] raw) {
        if (raw.length != profile.dim()) {
            throw new CodeSearchException("embedder_invalid_output", "embedding has wrong dimension or non-finite values");
        }
        double sum = 0;
        for (float x : raw) {
            if (!Float.isFinite(x)) {
                throw new CodeSearchException("embedder_invalid_output", "embedding has wrong dimension or non-finite values");
            }
            sum += (double) x * x;
        }
        double norm = Math.sqrt(sum);
        if (norm < 1e-8) {
            throw new CodeSearchException("embedder_invalid_output", "zero-norm embedding");
        }
        float[] unit = new float[raw.length];
        for (int i = 0; i < raw.length; i++) {
            unit[i] = (float) (raw[i] / norm);
        }
        return unit;
    }

    @Override
    public EmbeddingProfile profile() {
        return profile;
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        List<float[]> out = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += BATCH) {
            List<String> batch = texts.subList(i, Math.min(i + BATCH, texts.size())).stream()
                    .map(t -> profile.docPrefix() + t).toList();
            for (float[] v : embed(client, model, batch)) {
                out.add(checked(v));
            }
        }
        return out;
    }

    @Override
    public float[] embedQuery(String text) {
        return checked(embed(client, model, List.of(profile.queryPrefix() + text)).get(0));
    }
}
