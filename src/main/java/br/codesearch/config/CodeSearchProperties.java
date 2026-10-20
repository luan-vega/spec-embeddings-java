package br.codesearch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuração em {@code code-search.*}; os nomes de ambiente são os mesmos da versão Python (CODE_SEARCH_*), mapeados em
 * application.yml. {@code home} vazio significa {@code ~/.workflows/code-search}.
 */
@ConfigurationProperties("code-search")
public record CodeSearchProperties(
        String home, String embedder, String ollamaUrl, String ollamaModel, String docPrefix, String queryPrefix,
        int maxConcurrent, int timeoutSeconds, boolean allowRemote) {

    public CodeSearchProperties {
        embedder = embedder == null || embedder.isBlank() ? "ollama" : embedder;
        ollamaUrl = ollamaUrl == null || ollamaUrl.isBlank() ? "http://127.0.0.1:11434" : ollamaUrl;
        ollamaModel = ollamaModel == null ? "" : ollamaModel;
        docPrefix = docPrefix == null ? "" : docPrefix;
        queryPrefix = queryPrefix == null ? "" : queryPrefix;
        maxConcurrent = maxConcurrent <= 0 ? 4 : maxConcurrent;
        timeoutSeconds = timeoutSeconds <= 0 ? 30 : timeoutSeconds;
    }
}
