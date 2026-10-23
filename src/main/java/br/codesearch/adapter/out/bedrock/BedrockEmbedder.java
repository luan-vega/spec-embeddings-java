package br.codesearch.adapter.out.bedrock;

import br.codesearch.application.port.Embedder;
import br.codesearch.domain.CodeSearchException;
import br.codesearch.domain.EmbeddingProfile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * EXTERNO: embeddings do AWS Bedrock, famílias Amazon Titan Text Embeddings ({@code amazon.titan-embed-text-v2:0}) e Cohere
 * Embed ({@code cohere.embed-multilingual-v3}, {@code cohere.embed-v4:0}); a família sai do id do modelo (aceita perfis de
 * inferência como {@code us.cohere.embed-v4:0}). Cohere separa documento de consulta por {@code input_type}, então não usa prefixos.
 * O texto enviado sai da máquina. O perfil é próprio (modelo, dimensão): o índice é incompatível com os de outros embedders.
 */
public final class BedrockEmbedder implements Embedder {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int COHERE_BATCH = 96;

    private enum Family { TITAN, COHERE }

    private final BedrockConnection connection;
    private final String model;
    private final Family family;
    private final int dimensions;
    private final EmbeddingProfile profile;

    public BedrockEmbedder(BedrockConnection connection, String model, int dimensions) {
        if (model == null || model.isBlank()) {
            throw new CodeSearchException("embedder_unavailable", "CODE_SEARCH_BEDROCK_MODEL is required");
        }
        this.connection = connection;
        this.model = model.strip();
        this.family = familyOf(this.model);
        this.dimensions = dimensions;
        int dim = embed(List.of("probe"), "search_document").get(0).length;
        this.profile = new EmbeddingProfile(this.model, "remote:bedrock" + (dimensions > 0 ? ":" + dimensions : ""), dim, "", "", 0);
    }

    private static Family familyOf(String model) {
        if (model.contains("titan-embed")) {
            return Family.TITAN;
        }
        if (model.contains("cohere.embed")) {
            return Family.COHERE;
        }
        throw new CodeSearchException("embedder_unavailable",
                "unsupported Bedrock embedding model '" + model + "'; available families: amazon.titan-embed-*, cohere.embed-*");
    }

    public String host() {
        return connection.host();
    }

    private JsonNode invoke(Map<String, Object> body) {
        InvokeModelResponse resp;
        try {
            resp = connection.client().invokeModel(r -> r.modelId(model).contentType("application/json")
                    .accept("application/json").body(SdkBytes.fromByteArray(JSON.writeValueAsBytes(body))));
        } catch (RuntimeException e) {
            throw BedrockConnection.failure(e, "embedder_unavailable", "Bedrock failed to embed");
        }
        try {
            return JSON.readTree(resp.body().asByteArray());
        } catch (JacksonException e) {
            throw new CodeSearchException("embedder_invalid_output", "Bedrock returned invalid JSON");
        }
    }

    private List<float[]> embed(List<String> texts, String inputType) {
        return family == Family.TITAN ? embedTitan(texts) : embedCohere(texts, inputType);
    }

    /** Titan aceita um texto por chamada. */
    private List<float[]> embedTitan(List<String> texts) {
        List<float[]> out = new ArrayList<>();
        for (String text : texts) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("inputText", text);
            if (model.contains("v2")) {
                body.put("normalize", true);
                if (dimensions > 0) {
                    body.put("dimensions", dimensions);
                }
            }
            out.add(vector(invoke(body).path("embedding")));
        }
        return out;
    }

    private List<float[]> embedCohere(List<String> texts, String inputType) {
        List<float[]> out = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += COHERE_BATCH) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("texts", texts.subList(i, Math.min(i + COHERE_BATCH, texts.size())));
            body.put("input_type", inputType);
            body.put("truncate", "END");
            if (model.contains("embed-v4")) {
                body.put("embedding_types", List.of("float"));
                if (dimensions > 0) {
                    body.put("output_dimension", dimensions);
                }
            }
            JsonNode embeddings = invoke(body).path("embeddings");
            JsonNode rows = embeddings.isArray() ? embeddings : embeddings.path("float");
            int expected = Math.min(COHERE_BATCH, texts.size() - i);
            if (!rows.isArray() || rows.size() != expected) {
                throw new CodeSearchException("embedder_invalid_output", "unexpected embeddings shape");
            }
            for (JsonNode row : rows) {
                out.add(vector(row));
            }
        }
        return out;
    }

    private static float[] vector(JsonNode row) {
        if (!row.isArray() || row.isEmpty()) {
            throw new CodeSearchException("embedder_invalid_output", "unexpected embeddings shape");
        }
        float[] v = new float[row.size()];
        for (int j = 0; j < v.length; j++) {
            if (!row.get(j).isNumber()) {
                throw new CodeSearchException("embedder_invalid_output", "malformed embeddings");
            }
            v[j] = row.get(j).floatValue();
        }
        return v;
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
        embed(texts, "search_document").forEach(v -> out.add(checked(v)));
        return out;
    }

    @Override
    public float[] embedQuery(String text) {
        return checked(embed(List.of(text), "search_query").get(0));
    }
}
