package br.codesearch.adapter.in;

import br.codesearch.adapter.in.cli.CliCommands;
import br.codesearch.adapter.out.file.FileEmbeddingCache;
import br.codesearch.adapter.out.file.FilePolicies;
import br.codesearch.adapter.out.file.FileRepoRegistry;
import br.codesearch.adapter.out.file.FileVectorStore;
import br.codesearch.adapter.out.git.GitSource;
import br.codesearch.adapter.out.sparse.JavaSparseEncoder;
import br.codesearch.adapter.AdaptersTest;
import br.codesearch.application.AskService;
import br.codesearch.application.BoundedRunner;
import br.codesearch.application.IndexService;
import br.codesearch.application.SearchService;
import br.codesearch.application.StatusService;
import br.codesearch.application.fakes.BagOfWordsEmbedder;
import br.codesearch.application.fakes.ScriptedLanguageModel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Monta os adapters reais de arquivo com um embedder falso, como a raiz de composição faz com o Ollama. */
public final class TestApp {

    private final Path home;
    public final BagOfWordsEmbedder embedder = new BagOfWordsEmbedder();
    public final BoundedRunner runner = new BoundedRunner(2, 5000, 1);
    public final ScriptedLanguageModel validator = new ScriptedLanguageModel("fake:fast", false);
    public final ScriptedLanguageModel answerer = new ScriptedLanguageModel("fake:strong", false);

    public TestApp(Path home) {
        this.home = home;
    }

    /** Remonta os serviços a cada comando, como cada execução da CLI faz: a política é lida de novo do disco. */
    public CliCommands cliWith(br.codesearch.application.port.ProviderCatalog providers) {
        return build(providers);
    }

    private CliCommands cli() {
        return build(null);
    }

    private CliCommands build(br.codesearch.application.port.ProviderCatalog providers) {
        FileRepoRegistry registry = new FileRepoRegistry(home.resolve("repositories.json"));
        FilePolicies policies = new FilePolicies(home.resolve("exclusion-policy.json"));
        FileVectorStore store = new FileVectorStore(home.resolve("index"));
        JavaSparseEncoder sparse = new JavaSparseEncoder();
        StatusService status = new StatusService(store, registry, policies.load(), () -> embedder);
        SearchService search = new SearchService(store, registry, embedder, sparse);
        IndexService indexer = new IndexService(store, registry, embedder, sparse, policies.load(),
                new FileEmbeddingCache(home.resolve("cache"), embedder.profile()),
                entry -> new GitSource(Path.of(entry.path()), entry.ref()), null);
        AskService ask = new AskService(search, validator, answerer, policies.load());
        return new CliCommands(registry, policies, store, status, runner, () -> embedder, () -> indexer, () -> search, providers,
                () -> ask);
    }

    /** Repositório Git com uma classe de pagamento e outra de cliente. */
    public static Path gitRepo(Path dir) throws Exception {
        Path file = dir.resolve("src/main/java/br/Pagamento.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                package br;
                public class Pagamento {
                    /** Calcula juros compostos da parcela. */
                    public double calcularJurosComposto(double valor, int meses) { return valor * meses; }
                    public void estornarPagamento(String contaId) { System.out.println(contaId); }
                }
                """);
        AdaptersTest.git(dir, "init", "-q");
        AdaptersTest.git(dir, "add", "-A");
        AdaptersTest.git(dir, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "c");
        return dir;
    }

    public record Result(int code, String out, String err) {}

    public Result runWithInput(String stdin, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = cli().run(List.of(args), new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    public Result run(String... args) {
        return runWithInput("", args);
    }
}
