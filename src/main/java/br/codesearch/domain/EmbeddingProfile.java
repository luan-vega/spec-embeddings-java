package br.codesearch.domain;

import java.util.HashMap;
import java.util.Map;

/**
 * Tudo que, ao mudar, torna os vetores incompatíveis. O {@link #profileId()} é idêntico ao da versão Python
 * (SHA-256 do JSON canônico, ver {@link CanonicalJson}), para os índices serem comparáveis.
 */
public record EmbeddingProfile(
        String model, String digest, int dim, String docPrefix, String queryPrefix, int maxInputTokens) {

    public EmbeddingProfile {
        if (dim <= 0) {
            throw new IllegalArgumentException("dim must be positive");
        }
        docPrefix = docPrefix == null ? "" : docPrefix;
        queryPrefix = queryPrefix == null ? "" : queryPrefix;
    }

    public String profileId() {
        Map<String, Object> fields = new HashMap<>();
        fields.put("model", model);
        fields.put("digest", digest);
        fields.put("dim", dim);
        fields.put("metric", "cosine");
        fields.put("normalized", true);
        fields.put("doc_prefix", docPrefix);
        fields.put("query_prefix", queryPrefix);
        fields.put("max_input_tokens", maxInputTokens);
        return CanonicalJson.shortDigest(fields);
    }
}
