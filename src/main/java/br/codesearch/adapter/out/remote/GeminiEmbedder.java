package br.codesearch.adapter.out.remote;

import br.codesearch.application.port.Embedder;
import br.codesearch.domain.CodeSearchException;
import br.codesearch.domain.EmbeddingProfile;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * EXTERNO: embeddings pela API do Gemini ({@code batchEmbedContents}). O texto enviado sai da máquina. Documentos usam
 * RETRIEVAL_DOCUMENT e consultas CODE_RETRIEVAL_QUERY, então não há prefixos de texto. Com dimensão reduzida o Gemini não
 * normaliza; a normalização é feita aqui. Perfil próprio: o índice é incompatível com o de outros embedders.
 */
public final class GeminiEmbedder implements Embedder {

    private static final int BATCH = 100;

    private final RemoteHttp http;
    private final String model;
    private final int dimensions;
    private final EmbeddingProfile profile;

    public GeminiEmbedder(String baseUrl, String apiKey, String model, int dimensions) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new CodeSearchException("embedder_unavailable", "GEMINI_API_KEY is required for the gemini embedder");
        }
        if (model == null || model.isBlank()) {
            throw new CodeSearchException("embedder_unavailable", "CODE_SEARCH_GEMINI_MODEL is required");
        }
        this.http = new RemoteHttp(baseUrl, Map.of("x-goog-api-key", apiKey), Duration.ofSeconds(60), "embedder_unavailable");
        this.model = model;
        this.dimensions = dimensions;
        int dim = embed(List.of("probe"), "RETRIEVAL_DOCUMENT").get(0).length;
        this.profile = new EmbeddingProfile(model, "remote:gemini" + (dimensions > 0 ? ":" + dimensions : ""), dim, "", "", 0);
    }

    public String host() {
        return http.host();
    }

    private List<float[]> embed(List<String> texts, String taskType) {
        List<Map<String, Object>> requests = new ArrayList<>();
        for (String text : texts) {
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("model", "models/" + model);
            req.put("content", Map.of("parts", List.of(Map.of("text", text))));
            req.put("taskType", taskType);
            if (dimensions > 0) {
                req.put("outputDimensionality", dimensions);
            }
            requests.add(req);
        }
        RemoteHttp.Response resp = http.post("models/" + model + ":batchEmbedContents", Map.of("requests", requests));
        if (resp.status() != 200 || resp.body() == null) {
            throw http.failure(resp, "Gemini failed to embed");
        }
        JsonNode data = resp.body().path("embeddings");
        if (!data.isArray() || data.size() != texts.size()) {
            throw new CodeSearchException("embedder_invalid_output", "unexpected embeddings shape");
        }
        List<float[]> out = new ArrayList<>();
        for (int i = 0; i < data.size(); i++) {
            JsonNode row = data.get(i).path("values");
            if (!row.isArray() || row.isEmpty() || (!out.isEmpty() && row.size() != out.get(0).length)) {
                throw new CodeSearchException("embedder_invalid_output", "unexpected embeddings shape");
            }
            float[] v = new float[row.size()];
            for (int j = 0; j < v.length; j++) {
                if (!row.get(j).isNumber()) {
                    throw new CodeSearchException("embedder_invalid_output", "malformed embeddings");
                }
                v[j] = row.get(j).floatValue();
            }
            out.add(v);
        }
        return out;
    }

    private float[] checked(float[] raw) {
        double sum = 0;
        for (float x : raw) {
            if (!Float.isFinite(x)) {
                throw new CodeSearchException("embedder_invalid_output", "embedding has wrong dimension or non-finite values");
            }
            sum += (double) x * x;
        }
        double norm = Math.sqrt(sum);
        if (raw.length != profile.dim() || norm < 1e-8) {
            throw new CodeSearchException("embedder_invalid_output", "embedding has wrong dimension or zero norm");
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
    public boolean local() {
        return false;
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        List<float[]> out = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += BATCH) {
            embed(texts.subList(i, Math.min(i + BATCH, texts.size())), "RETRIEVAL_DOCUMENT").forEach(v -> out.add(checked(v)));
        }
        return out;
    }

    @Override
    public float[] embedQuery(String text) {
        return checked(embed(List.of(text), "CODE_RETRIEVAL_QUERY").get(0));
    }
}
