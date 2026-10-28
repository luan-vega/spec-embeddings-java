package br.codesearch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuração em {@code code-search.*}; os nomes de ambiente são os mesmos da versão Python (CODE_SEARCH_*), mapeados em
 * application.yml. {@code home} vazio significa {@code ~/.workflows/code-search}. As chaves de API (OPENAI_API_KEY,
 * ANTHROPIC_API_KEY, GEMINI_API_KEY e a cadeia de credenciais da AWS para o bedrock) não ficam aqui: são lidas só no momento de criar o adapter, para nunca aparecerem em toString ou logs.
 *
 * <p>{@code embedder}: ollama (local), openai, gemini ou bedrock (EXTERNOS). {@code describer}: vazio (sem descrição), ollama (local), openai,
 * claude, gemini ou bedrock (EXTERNOS). {@code llm} (pergunta, comando ask): claude (padrão), openai, gemini, bedrock (EXTERNOS) ou ollama;
 * {@code llmFastModel} valida e reescreve a pergunta, {@code llmModel} responde. Os externos só funcionam com {@code allowRemote}.
 *
 * <p>{@code graph}: GraphRAG no ask (vizinhos no grafo de código entram no contexto), desligado por padrão;
 * {@code graphHops} (1 ou 2) e {@code graphMaxExtra} (trechos a mais, 0 a 20) ajustam a expansão.
 */
@ConfigurationProperties("code-search")
public record CodeSearchProperties(
        String home, String embedder, String ollamaUrl, String ollamaModel, String docPrefix, String queryPrefix,
        int maxConcurrent, int timeoutSeconds, boolean allowRemote, String describer, String describerModel,
        String openaiBaseUrl, String openaiModel, int openaiDimensions, String anthropicBaseUrl,
        String geminiBaseUrl, String geminiModel, int geminiDimensions, String llm, String llmModel, String llmFastModel,
        String bedrockRegion, String bedrockEndpoint, String bedrockModel, int bedrockDimensions, boolean graph,
        int graphHops, int graphMaxExtra) {

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
        bedrockRegion = bedrockRegion == null ? "" : bedrockRegion.strip();
        bedrockEndpoint = bedrockEndpoint == null ? "" : bedrockEndpoint.strip();
        bedrockModel = bedrockModel == null || bedrockModel.isBlank() ? "cohere.embed-multilingual-v3" : bedrockModel.strip();
        graphHops = graphHops <= 0 ? 1 : graphHops;
        graphMaxExtra = graphMaxExtra < 0 ? 4 : graphMaxExtra;
        anthropicBaseUrl = anthropicBaseUrl == null || anthropicBaseUrl.isBlank() ? "https://api.anthropic.com" : anthropicBaseUrl;
    }
}
