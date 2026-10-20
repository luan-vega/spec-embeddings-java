package br.codesearch.config;

import br.codesearch.adapter.out.file.FileEmbeddingCache;
import br.codesearch.adapter.out.file.FilePolicies;
import br.codesearch.adapter.out.file.FileRepoRegistry;
import br.codesearch.adapter.out.file.FileVectorStore;
import br.codesearch.adapter.out.git.GitSource;
import br.codesearch.adapter.out.ollama.OllamaClient;
import br.codesearch.adapter.out.ollama.OllamaEmbedder;
import br.codesearch.adapter.out.sparse.JavaSparseEncoder;
import br.codesearch.application.BoundedRunner;
import br.codesearch.application.IndexService;
import br.codesearch.application.SearchService;
import br.codesearch.application.StatusService;
import br.codesearch.application.port.EmbeddingCache;
import br.codesearch.application.port.Embedder;
import br.codesearch.application.port.PolicyStore;
import br.codesearch.application.port.RepoCatalog;
import br.codesearch.application.port.SparseEncoder;
import br.codesearch.application.port.VectorStore;
import br.codesearch.domain.CodeSearchException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Supplier;
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

    @Bean
    @Lazy
    Embedder embedder(CodeSearchProperties props) {
        if (!props.embedder().equals("ollama")) {
            throw new CodeSearchException("embedder_unavailable", "unknown embedder '" + props.embedder() + "'; available: ollama");
        }
        return new OllamaEmbedder(props.ollamaModel(), new OllamaClient(props.ollamaUrl(), Duration.ofSeconds(60)),
                props.docPrefix(), props.queryPrefix());
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

    @Bean
    @Lazy
    IndexService indexService(
            VectorStore store, RepoCatalog registry, Embedder embedder, SparseEncoder sparse, PolicyStore policies,
            EmbeddingCache cache) {
        return new IndexService(store, registry, embedder, sparse, policies.load(), cache,
                entry -> new GitSource(Path.of(entry.path()), entry.ref()), null);
    }

    @Bean
    StatusService statusService(VectorStore store, RepoCatalog registry, PolicyStore policies,
            org.springframework.beans.factory.ObjectProvider<Embedder> embedder) {
        Supplier<Embedder> supplier = () -> CodeSearchException.resolve(embedder::getObject);
        return new StatusService(store, registry, policies.load(), supplier);
    }
}
