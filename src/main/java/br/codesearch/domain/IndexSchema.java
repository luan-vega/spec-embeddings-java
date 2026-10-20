package br.codesearch.domain;

import java.util.HashMap;
import java.util.Map;

/** Parâmetros de construção do índice que não são do modelo. {@code describerId} vazio: sem descrição de trechos. */
public record IndexSchema(
        String chunkerVersion, String tokenizerVersion, String policyVersion, double bm25K1, double bm25B,
        String describerId) {

    public IndexSchema(String chunkerVersion, String tokenizerVersion, String policyVersion) {
        this(chunkerVersion, tokenizerVersion, policyVersion, 1.2, 0.75, "");
    }

    public IndexSchema {
        describerId = describerId == null ? "" : describerId;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> fields = new HashMap<>();
        fields.put("chunker_version", chunkerVersion);
        fields.put("tokenizer_version", tokenizerVersion);
        fields.put("policy_version", policyVersion);
        fields.put("bm25_k1", bm25K1);
        fields.put("bm25_b", bm25B);
        if (!describerId.isEmpty()) { // índices sem descritor mantêm o mesmo schema_id de antes
            fields.put("describer_id", describerId);
        }
        return fields;
    }

    public String schemaId() {
        return CanonicalJson.shortDigest(toMap());
    }
}
