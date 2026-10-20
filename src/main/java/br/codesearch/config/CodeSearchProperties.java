package br.codesearch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuração em {@code code-search.*}; os nomes de ambiente são os mesmos da versão Python (CODE_SEARCH_*), mapeados em
 * application.yml. {@code home} vazio significa {@code ~/.workflows/code-search}. As chaves de API (OPENAI_API_KEY,
 * ANTHROPIC_API_KEY) não ficam aqui: são lidas só no momento de criar o adapter, para nunca aparecerem em toString ou logs.
 *
 * <p>{@code embedder}: ollama (local) ou openai (EXTERNO). {@code describer}: vazio (sem descrição), ollama (local), openai ou
 * claude (EXTERNOS). Os externos só funcionam com {@code allowRemote}.
 */
@ConfigurationProperties("code-search")
public record CodeSearchProperties(
        String home, String embedder, String ollamaUrl, String ollamaModel, String docPrefix, String queryPrefix,
        int maxConcurrent, int timeoutSeconds, boolean allowRemote, String describer, String describerModel,
        String openaiBaseUrl, String openaiModel, int openaiDimensions, String anthropicBaseUrl) {

    public CodeSearchProperties {
        embedder = embedder == null || embedder.isBlank() ? "ollama" : embedder;
        ollamaUrl = ollamaUrl == null || ollamaUrl.isBlank() ? "http://127.0.0.1:11434" : ollamaUrl;
        ollamaModel = ollamaModel == null ? "" : ollamaModel;
        docPrefix = docPrefix == null ? "" : docPrefix;
        queryPrefix = queryPrefix == null ? "" : queryPrefix;
        maxConcurrent = maxConcurrent <= 0 ? 4 : maxConcurrent;
        timeoutSeconds = timeoutSeconds <= 0 ? 30 : timeoutSeconds;
        describer = describer == null ? "" : describer.strip();
        describerModel = describerModel == null ? "" : describerModel;
        openaiBaseUrl = openaiBaseUrl == null || openaiBaseUrl.isBlank() ? "https://api.openai.com/v1" : openaiBaseUrl;
        openaiModel = openaiModel == null || openaiModel.isBlank() ? "text-embedding-3-small" : openaiModel;
        anthropicBaseUrl = anthropicBaseUrl == null || anthropicBaseUrl.isBlank() ? "https://api.anthropic.com" : anthropicBaseUrl;
    }
}
