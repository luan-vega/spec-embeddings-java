package br.codesearch.domain;

import java.util.Map;

/** Erro com código estável, compartilhado por todas as camadas. A mensagem nunca carrega caminhos absolutos. */
public class CodeSearchException extends RuntimeException {

    private static final Map<String, Integer> HTTP_STATUS = Map.ofEntries(
            Map.entry("invalid_request", 400),
            Map.entry("no_repos_available", 404),
            Map.entry("repo_unavailable", 404),
            Map.entry("profile_mismatch", 409),
            Map.entry("embedder_unavailable", 503),
            Map.entry("describer_unavailable", 503),
            Map.entry("llm_unavailable", 503),
            Map.entry("vector_store_unavailable", 503),
            Map.entry("store_busy", 503),
            Map.entry("overloaded", 503),
            Map.entry("timeout", 504),
            Map.entry("payload_too_large", 413),
            Map.entry("not_found", 404),
            Map.entry("method_not_allowed", 405),
            Map.entry("internal_error", 500),
            Map.entry("embedder_invalid_output", 502));

    private final String code;

    public CodeSearchException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    /**
     * Chama o fornecedor e devolve o {@link CodeSearchException} original se a falha veio embrulhada (o contêiner de beans
     * embrulha o erro de criação de um bean), para o erro sair com o código estável e não como falha interna.
     */
    public static <T> T resolve(java.util.function.Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException e) {
            for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
                if (t instanceof CodeSearchException c) {
                    throw c;
                }
            }
            throw e;
        }
    }

    public int httpStatus() {
        return HTTP_STATUS.getOrDefault(code, 500);
    }
}
