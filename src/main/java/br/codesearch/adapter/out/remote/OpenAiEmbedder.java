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
 * EXTERNO: embeddings pela API da OpenAI (ou qualquer servidor compatível em {@code /embeddings}). O texto enviado sai da
 * máquina. O perfil é próprio (modelo, dimensão), então o índice é incompatível com o do Ollama e fica em outro home.
 */
public final class OpenAiEmbedder implements Embedder {

    private static final int BATCH = 64;

    private final RemoteHttp http;
    private final String model;
    private final int dimensions;
    private final EmbeddingProfile profile;

    public OpenAiEmbedder(String baseUrl, String apiKey, String model, int dimensions, String docPrefix, String queryPrefix) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new CodeSearchException("embedder_unavailable", "OPENAI_API_KEY is required for the openai embedder");
        }
        if (model == null || model.isBlank()) {
            throw new CodeSearchException("embedder_unavailable", "CODE_SEARCH_OPENAI_MODEL is required");
        }
        this.http = new RemoteHttp(baseUrl, Map.of("Authorization", "Bearer " + apiKey), Duration.ofSeconds(60), "embedder_unavailable");
        this.model = model;
        this.dimensions = dimensions;
        int dim = embed(List.of("probe")).get(0).length;
        this.profile = new EmbeddingProfile(model, "remote:openai" + (dimensions > 0 ? ":" + dimensions : ""), dim,
                docPrefix, queryPrefix, 0);
    }

    public String host() {
        return http.host();
    }

    private List<float[]> embed(List<String> texts) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", texts);
        body.put("encoding_format", "float");
        if (dimensions > 0) {
            body.put("dimensions", dimensions);
        }
        RemoteHttp.Response resp = http.post("embeddings", body);
        if (resp.status() != 200 || resp.body() == null) {
            throw http.failure(resp, "OpenAI failed to embed");
        }
        JsonNode data = resp.body().path("data");
        if (!data.isArray() || data.size() != texts.size()) {
            throw new CodeSearchException("embedder_invalid_output", "unexpected embeddings shape");
        }
        List<float[]> out = new ArrayList<>();
        for (int i = 0; i < data.size(); i++) {
            JsonNode row = data.get(i).path("embedding");
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
            List<String> batch = texts.subList(i, Math.min(i + BATCH, texts.size())).stream()
                    .map(t -> profile.docPrefix() + t).toList();
            embed(batch).forEach(v -> out.add(checked(v)));
        }
        return out;
    }

    @Override
    public float[] embedQuery(String text) {
        return checked(embed(List.of(profile.queryPrefix() + text)).get(0));
    }
}
