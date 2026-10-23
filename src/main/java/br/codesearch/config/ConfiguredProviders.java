package br.codesearch.config;

import br.codesearch.application.ProviderInfo;
import br.codesearch.application.port.ProviderCatalog;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Lista os provedores e o que falta para cada um, só lendo a configuração e a existência das chaves no ambiente. */
@Component
class ConfiguredProviders implements ProviderCatalog {

    private final CodeSearchProperties props;
    private final Environment env;

    ConfiguredProviders(CodeSearchProperties props, Environment env) {
        this.props = props;
        this.env = env;
    }

    @Override
    public boolean allowRemote() {
        return props.allowRemote();
    }

    private String bedrockHost() {
        if (!props.bedrockEndpoint().isBlank()) {
            return props.bedrockEndpoint();
        }
        return props.bedrockRegion().isBlank() ? "bedrock-runtime.<região>.amazonaws.com"
                : "bedrock-runtime." + props.bedrockRegion() + ".amazonaws.com";
    }

    private boolean has(String variable) {
        String value = env.getProperty(variable);
        return value != null && !value.isBlank();
    }

    private List<String> missing(String keyVariable, String model, String modelVariable) {
        List<String> out = new ArrayList<>();
        if (keyVariable != null && !has(keyVariable)) {
            out.add(keyVariable);
        }
        if (model.isBlank()) {
            out.add(modelVariable);
        }
        return out;
    }

    /** Região e credenciais da AWS: a cadeia padrão não é inspecionada, só as variáveis mais comuns. */
    private List<String> bedrockMissing(String model, String modelVariable) {
        List<String> out = new ArrayList<>();
        if (props.bedrockRegion().isBlank() && !has("AWS_DEFAULT_REGION") && !has("AWS_PROFILE")) {
            out.add("CODE_SEARCH_BEDROCK_REGION (ou AWS_REGION)");
        }
        if (!has("AWS_ACCESS_KEY_ID") && !has("AWS_PROFILE") && !has("AWS_CONTAINER_CREDENTIALS_RELATIVE_URI")
                && !has("AWS_WEB_IDENTITY_TOKEN_FILE")) {
            out.add("credenciais AWS (AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY, AWS_PROFILE ou role)");
        }
        if (model.isBlank()) {
            out.add(modelVariable);
        }
        return out;
    }

    @Override
    public List<ProviderInfo> providers() {
        String describerModel = props.describerModel();
        String chosenEmbedder = props.embedder();
        String chosenDescriber = props.describer();
        String chosenLlm = props.llm();
        String llmModel = props.llmModel();
        return List.of(
                new ProviderInfo("embedder", "ollama", false, chosenEmbedder.equals("ollama"), props.ollamaModel(),
                        props.ollamaUrl(), missing(null, props.ollamaModel(), "CODE_SEARCH_OLLAMA_MODEL")),
                new ProviderInfo("embedder", "openai", true, chosenEmbedder.equals("openai"), props.openaiModel(),
                        props.openaiBaseUrl(), missing("OPENAI_API_KEY", props.openaiModel(), "CODE_SEARCH_OPENAI_MODEL")),
                new ProviderInfo("embedder", "gemini", true, chosenEmbedder.equals("gemini"), props.geminiModel(),
                        props.geminiBaseUrl(), missing("GEMINI_API_KEY", props.geminiModel(), "CODE_SEARCH_GEMINI_MODEL")),
                new ProviderInfo("embedder", "bedrock", true, chosenEmbedder.equals("bedrock"), props.bedrockModel(),
                        bedrockHost(), bedrockMissing(props.bedrockModel(), "CODE_SEARCH_BEDROCK_MODEL")),
                new ProviderInfo("describer", "ollama", false, chosenDescriber.equals("ollama"), describerModel,
                        props.ollamaUrl(), missing(null, describerModel, "CODE_SEARCH_DESCRIBER_MODEL")),
                new ProviderInfo("describer", "openai", true, chosenDescriber.equals("openai"), describerModel,
                        props.openaiBaseUrl(), missing("OPENAI_API_KEY", describerModel, "CODE_SEARCH_DESCRIBER_MODEL")),
                new ProviderInfo("describer", "claude", true, chosenDescriber.equals("claude"),
                        describerModel.isBlank() ? "claude-haiku-5-5" : describerModel, props.anthropicBaseUrl(),
                        missing("ANTHROPIC_API_KEY", "claude-haiku-5-5", "CODE_SEARCH_DESCRIBER_MODEL")),
                new ProviderInfo("describer", "gemini", true, chosenDescriber.equals("gemini"),
                        describerModel.isBlank() ? "gemini-2.5-flash" : describerModel, props.geminiBaseUrl(),
                        missing("GEMINI_API_KEY", "gemini-2.5-flash", "CODE_SEARCH_DESCRIBER_MODEL")),
                new ProviderInfo("llm", "claude", true, chosenLlm.equals("claude"),
                        llmModel.isBlank() ? "claude-opus-5-5" : llmModel, props.anthropicBaseUrl(),
                        missing("ANTHROPIC_API_KEY", "claude-opus-5-5", "CODE_SEARCH_LLM_MODEL")),
                new ProviderInfo("llm", "openai", true, chosenLlm.equals("openai"), llmModel, props.openaiBaseUrl(),
                        missing("OPENAI_API_KEY", llmModel, "CODE_SEARCH_LLM_MODEL")),
                new ProviderInfo("llm", "gemini", true, chosenLlm.equals("gemini"),
                        llmModel.isBlank() ? "gemini-2.5-flash" : llmModel, props.geminiBaseUrl(),
                        missing("GEMINI_API_KEY", "gemini-2.5-flash", "CODE_SEARCH_LLM_MODEL")),
                new ProviderInfo("describer", "bedrock", true, chosenDescriber.equals("bedrock"), describerModel,
                        bedrockHost(), bedrockMissing(describerModel, "CODE_SEARCH_DESCRIBER_MODEL")),
                new ProviderInfo("llm", "bedrock", true, chosenLlm.equals("bedrock"), llmModel, bedrockHost(),
                        bedrockMissing(llmModel, "CODE_SEARCH_LLM_MODEL")),
                new ProviderInfo("llm", "ollama", false, chosenLlm.equals("ollama"), llmModel, props.ollamaUrl(),
                        missing(null, llmModel, "CODE_SEARCH_LLM_MODEL")));
    }
}
