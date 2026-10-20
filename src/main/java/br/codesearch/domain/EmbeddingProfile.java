package br.codesearch.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Tudo que, ao mudar, torna os vetores incompatíveis. O {@link #profileId()} é idêntico ao da versão Python
 * (SHA-256 do JSON canônico: chaves em ordem alfabética, sem espaços, UTF-8), para os índices serem comparáveis.
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
        String json = "{\"digest\":" + quote(digest)
                + ",\"dim\":" + dim
                + ",\"doc_prefix\":" + quote(docPrefix)
                + ",\"max_input_tokens\":" + maxInputTokens
                + ",\"metric\":\"cosine\""
                + ",\"model\":" + quote(model)
                + ",\"normalized\":true"
                + ",\"query_prefix\":" + quote(queryPrefix) + "}";
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
