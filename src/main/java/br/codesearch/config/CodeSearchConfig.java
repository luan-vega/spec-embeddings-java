package br.codesearch.config;

import br.codesearch.adapter.out.file.FileEmbeddingCache;
import br.codesearch.adapter.out.file.FilePolicies;
import br.codesearch.adapter.out.file.FileRepoRegistry;
import br.codesearch.adapter.out.file.FileVectorStore;
import br.codesearch.adapter.out.git.GitSource;
import br.codesearch.adapter.out.bedrock.BedrockChat;
import br.codesearch.adapter.out.bedrock.BedrockConnection;
import br.codesearch.adapter.out.bedrock.BedrockDescriber;
import br.codesearch.adapter.out.bedrock.BedrockEmbedder;
import br.codesearch.adapter.out.describer.OllamaDescriber;
import br.codesearch.adapter.out.ollama.OllamaChat;
import br.codesearch.adapter.out.ollama.OllamaClient;
import br.codesearch.adapter.out.ollama.OllamaEmbedder;
import br.codesearch.adapter.out.remote.GeminiEmbedder;
import br.codesearch.adapter.out.remote.OpenAiEmbedder;
import br.codesearch.adapter.out.remote.RemoteChats;
import br.codesearch.adapter.out.remote.RemoteDescribers;
import br.codesearch.adapter.out.sparse.JavaSparseEncoder;
import br.codesearch.application.AskService;
import br.codesearch.application.GraphContext;
import br.codesearch.application.BoundedRunner;
import br.codesearch.application.IndexService;
import br.codesearch.application.SearchService;
import br.codesearch.application.StatusService;
import br.codesearch.application.port.ChunkDescriber;
import br.codesearch.application.port.EmbeddingCache;
import br.codesearch.application.port.Embedder;
import br.codesearch.application.port.LanguageModel;
import br.codesearch.application.port.PolicyStore;
import br.codesearch.application.port.RepoCatalog;
import br.codesearch.application.port.SparseEncoder;
import br.codesearch.application.port.VectorStore;
import br.codesearch.domain.CodeSearchException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Raiz de composição: as dependências são criadas uma vez e injetadas; CLI, HTTP e MCP usam o MESMO serviço. Para trocar
 * o Ollama por outro provedor, adicione um {@code case} em {@link #embedder} (ver docs de arquitetura).
 * O embedder e tudo que depende dele são {@code @Lazy}: registrar repositórios ou ver o status não exige o Ollama no ar.
 */
@Configuration
@EnableConfigurationProperties(CodeSearchProperties.class)
class CodeSearchConfig {

    @Bean
    DataPaths dataPaths(CodeSearchProperties props) {
        return DataPaths.of(props.home());
    }

    @Bean
    RepoCatalog repoCatalog(DataPaths paths) {
        return new FileRepoRegistry(paths.registry());
    }

    @Bean
    PolicyStore policyStore(DataPaths paths) {
        return new FilePolicies(paths.policy());
    }

    @Bean
    VectorStore vectorStore(DataPaths paths) {
        return new FileVectorStore(paths.index());
    }

    @Bean
    SparseEncoder sparseEncoder() {
        return new JavaSparseEncoder();
    }

    @Bean(destroyMethod = "close")
    BoundedRunner boundedRunner(CodeSearchProperties props) {
        return new BoundedRunner(props.maxConcurrent(), props.timeoutSeconds() * 1000L, 1);
    }

    private static BedrockConnection bedrock(CodeSearchProperties props, String errorCode) {
        return BedrockConnection.create(props.bedrockRegion(), props.bedrockEndpoint(), errorCode);
    }

    @Bean
    @Lazy
    Embedder embedder(CodeSearchProperties props, @Value("${OPENAI_API_KEY:}") String openaiKey,
            @Value("${GEMINI_API_KEY:}") String geminiKey) {
        switch (props.embedder()) {
            case "ollama" -> {
                return new OllamaEmbedder(props.ollamaModel(), new OllamaClient(props.ollamaUrl(), Duration.ofSeconds(60)),
                        props.docPrefix(), props.queryPrefix());
            }
            case "openai" -> {
                RemoteGate.require(props.allowRemote(), "embedder", "openai", "embedder_unavailable");
                OpenAiEmbedder embedder = new OpenAiEmbedder(props.openaiBaseUrl(), openaiKey, props.openaiModel(),
                        props.openaiDimensions(), props.docPrefix(), props.queryPrefix());
                RemoteGate.announce(System.err, "embedder", "openai", embedder.host(), "trechos de código e consultas");
                return embedder;
            }
            case "gemini" -> {
                RemoteGate.require(props.allowRemote(), "embedder", "gemini", "embedder_unavailable");
                GeminiEmbedder embedder = new GeminiEmbedder(props.geminiBaseUrl(), geminiKey, props.geminiModel(),
                        props.geminiDimensions());
                RemoteGate.announce(System.err, "embedder", "gemini", embedder.host(), "trechos de código e consultas");
                return embedder;
            }
            case "bedrock" -> {
                RemoteGate.require(props.allowRemote(), "embedder", "bedrock", "embedder_unavailable");
                var connection = bedrock(props, "embedder_unavailable");
                var embedder = new BedrockEmbedder(connection, props.bedrockModel(), props.bedrockDimensions());
                RemoteGate.announce(System.err, "embedder", "bedrock", embedder.host(), "trechos de código e consultas");
                return embedder;
            }
            default -> throw new CodeSearchException("embedder_unavailable",
                    "unknown embedder '" + props.embedder() + "'; available: ollama, openai, gemini, bedrock");
        }
    }

    @Bean
    @Lazy
    ChunkDescriber chunkDescriber(
            CodeSearchProperties props, @Value("${OPENAI_API_KEY:}") String openaiKey,
            @Value("${ANTHROPIC_API_KEY:}") String anthropicKey, @Value("${GEMINI_API_KEY:}") String geminiKey) {
        return new LazyDescriber(() -> switch (props.describer()) {
            case "" -> null;
            case "ollama" -> new OllamaDescriber(props.describerModel(), new OllamaClient(props.ollamaUrl(), Duration.ofSeconds(120)));
            case "openai" -> {
                RemoteGate.require(props.allowRemote(), "describer", "openai", "describer_unavailable");
                var d = new RemoteDescribers.OpenAi(props.openaiBaseUrl(), openaiKey, props.describerModel());
                RemoteGate.announce(System.err, "describer", "openai", d.host(), "o código dos métodos");
                yield d;
            }
            case "claude" -> {
                RemoteGate.require(props.allowRemote(), "describer", "claude", "describer_unavailable");
                String model = props.describerModel().isBlank() ? "claude-haiku-5-5" : props.describerModel();
                var d = new RemoteDescribers.Claude(props.anthropicBaseUrl(), anthropicKey, model);
                RemoteGate.announce(System.err, "describer", "claude", d.host(), "o código dos métodos");
                yield d;
            }
            case "gemini" -> {
                RemoteGate.require(props.allowRemote(), "describer", "gemini", "describer_unavailable");
                String model = props.describerModel().isBlank() ? "gemini-2.5-flash" : props.describerModel();
                var d = new RemoteDescribers.Gemini(props.geminiBaseUrl(), geminiKey, model);
                RemoteGate.announce(System.err, "describer", "gemini", d.host(), "o código dos métodos");
                yield d;
            }
            case "bedrock" -> {
                RemoteGate.require(props.allowRemote(), "describer", "bedrock", "describer_unavailable");
                var d = new BedrockDescriber(bedrock(props, "describer_unavailable"), props.describerModel());
                RemoteGate.announce(System.err, "describer", "bedrock", d.host(), "o código dos métodos");
                yield d;
            }
            default -> throw new CodeSearchException("describer_unavailable",
                    "unknown describer '" + props.describer() + "'; available: ollama, openai, claude, gemini, bedrock");
        });
    }

    @Bean
    @Lazy
    EmbeddingCache embeddingCache(DataPaths paths, Embedder embedder) {
        return new FileEmbeddingCache(paths.cache(), embedder.profile());
    }

    @Bean
    @Lazy
    SearchService searchService(VectorStore store, RepoCatalog registry, Embedder embedder, SparseEncoder sparse) {
        return new SearchService(store, registry, embedder, sparse);
    }

    /**
     * Modelo da pergunta. {@code fast}: o que valida e reescreve (vê só a pergunta); senão o que responde (vê a pergunta e
     * os trechos de código achados). Externo só com permissão explícita, como o embedder e o descritor.
     */
    LanguageModel languageModel(
            CodeSearchProperties props, boolean fast, String openaiKey, String anthropicKey, String geminiKey) {
        String chosen = fast && !props.llmFastModel().isBlank() ? props.llmFastModel() : props.llmModel();
        String sends = fast ? "as perguntas" : "as perguntas e os trechos de código achados";
        String role = fast ? "llm (validação)" : "llm (resposta)";
        switch (props.llm()) {
            case "claude" -> {
                RemoteGate.require(props.allowRemote(), "llm", "claude", "llm_unavailable");
                String model = !chosen.isBlank() ? chosen : fast ? "claude-haiku-5-5" : "claude-opus-5-5";
                var m = new RemoteChats.Claude(props.anthropicBaseUrl(), anthropicKey, model, fast ? "low" : "medium");
                RemoteGate.announce(System.err, role, "claude", m.host(), sends);
                return m;
            }
            case "openai" -> {
                RemoteGate.require(props.allowRemote(), "llm", "openai", "llm_unavailable");
                var m = new RemoteChats.OpenAi(props.openaiBaseUrl(), openaiKey, chosen);
                RemoteGate.announce(System.err, role, "openai", m.host(), sends);
                return m;
            }
            case "gemini" -> {
                RemoteGate.require(props.allowRemote(), "llm", "gemini", "llm_unavailable");
                var m = new RemoteChats.Gemini(props.geminiBaseUrl(), geminiKey, chosen.isBlank() ? "gemini-2.5-flash" : chosen);
                RemoteGate.announce(System.err, role, "gemini", m.host(), sends);
                return m;
            }
            case "bedrock" -> {
                RemoteGate.require(props.allowRemote(), "llm", "bedrock", "llm_unavailable");
                var m = new BedrockChat(bedrock(props, "llm_unavailable"), chosen);
                RemoteGate.announce(System.err, role, "bedrock", m.host(), sends);
                return m;
            }
            case "ollama" -> {
                return new OllamaChat(chosen, new OllamaClient(props.ollamaUrl(), Duration.ofSeconds(120)));
            }
            default -> throw new CodeSearchException("llm_unavailable",
                    "unknown llm '" + props.llm() + "'; available: claude, openai, gemini, bedrock, ollama");
        }
    }

    @Bean
    @Lazy
    AskService askService(
            CodeSearchProperties props, SearchService search, PolicyStore policies, GraphContext graphContext,
            @Value("${OPENAI_API_KEY:}") String openaiKey,
            @Value("${ANTHROPIC_API_KEY:}") String anthropicKey, @Value("${GEMINI_API_KEY:}") String geminiKey) {
        return new AskService(search, languageModel(props, true, openaiKey, anthropicKey, geminiKey),
                languageModel(props, false, openaiKey, anthropicKey, geminiKey), policies.load(), graphContext,
                props.graph());
    }

    /** Grafo de código do GraphRAG: montado na primeira pergunta que o usa e refeito quando a geração do índice muda. */
    @Bean
    @Lazy
    GraphContext graphContext(CodeSearchProperties props, VectorStore store) {
        return new GraphContext(store, props.graphHops(), props.graphMaxExtra());
    }

    @Bean
    @Lazy
    IndexService indexService(
            VectorStore store, RepoCatalog registry, Embedder embedder, SparseEncoder sparse, PolicyStore policies,
            EmbeddingCache cache, ChunkDescriber describer) {
        return new IndexService(store, registry, embedder, sparse, policies.load(), cache,
                entry -> new GitSource(Path.of(entry.path()), entry.ref()), describer);
    }

    @Bean
    StatusService statusService(VectorStore store, RepoCatalog registry, PolicyStore policies,
            org.springframework.beans.factory.ObjectProvider<Embedder> embedder) {
        Supplier<Embedder> supplier = () -> CodeSearchException.resolve(embedder::getObject);
        return new StatusService(store, registry, policies.load(), supplier);
    }
}
