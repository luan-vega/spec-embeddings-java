package br.codesearch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuração em {@code code-search.*}; os nomes de ambiente são os mesmos da versão Python (CODE_SEARCH_*), mapeados em
 * application.yml. {@code home} vazio significa {@code ~/.workflows/code-search}. As chaves de API (OPENAI_API_KEY,
 * ANTHROPIC_API_KEY, GEMINI_API_KEY) não ficam aqui: são lidas só no momento de criar o adapter, para nunca aparecerem em toString ou logs.
 *
 * <p>{@code embedder}: ollama (local), openai ou gemini (EXTERNOS). {@code describer}: vazio (sem descrição), ollama (local), openai,
 * claude ou gemini (EXTERNOS). {@code llm} (pergunta, comando ask): claude (padrão), openai, gemini (EXTERNOS) ou ollama;
 * {@code llmFastModel} valida e reescreve a pergunta, {@code llmModel} responde. Os externos só funcionam com {@code allowRemote}.
 */
@ConfigurationProperties("code-search")
public record CodeSearchProperties(
        String home, String embedder, String ollamaUrl, String ollamaModel, String docPrefix, String queryPrefix,
        int maxConcurrent, int timeoutSeconds, boolean allowRemote, String describer, String describerModel,
        String openaiBaseUrl, String openaiModel, int openaiDimensions, String anthropicBaseUrl,
        String geminiBaseUrl, String geminiModel, int geminiDimensions, String llm, String llmModel, String llmFastModel) {

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
        geminiBaseUrl = geminiBaseUrl == null || geminiBaseUrl.isBlank()
                ? "https://generativelanguage.googleapis.com/v1beta" : geminiBaseUrl;
        geminiModel = geminiModel == null || geminiModel.isBlank() ? "gemini-embedding-001" : geminiModel;
        llm = llm == null || llm.isBlank() ? "claude" : llm.strip();
        llmModel = llmModel == null ? "" : llmModel.strip();
        llmFastModel = llmFastModel == null ? "" : llmFastModel.strip();
        anthropicBaseUrl = anthropicBaseUrl == null || anthropicBaseUrl.isBlank() ? "https://api.anthropic.com" : anthropicBaseUrl;
    }
}
