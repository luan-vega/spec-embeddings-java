package br.codesearch.adapter.in.cli;

import br.codesearch.application.BoundedRunner;
import br.codesearch.application.IndexService;
import br.codesearch.application.SearchService;
import br.codesearch.application.StatusService;
import br.codesearch.application.port.Embedder;
import br.codesearch.application.port.PolicyStore;
import br.codesearch.application.port.RepoCatalog;
import br.codesearch.application.port.VectorStore;
import br.codesearch.domain.CodeSearchException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Executa o comando da linha de comando dentro do contexto Spring, com os MESMOS serviços do HTTP. Sem argumentos, ou com
 * {@code serve}, não faz nada: o servidor web cuida do resto. O código de saída fica em {@link #exitCode()}.
 */
@Component
public class CliRunner implements ApplicationRunner {

    private final CliCommands commands;
    private volatile int exitCode;

    CliRunner(
            RepoCatalog registry, PolicyStore policies, VectorStore store, StatusService status, BoundedRunner runner,
            ObjectProvider<Embedder> embedder, ObjectProvider<IndexService> indexer, ObjectProvider<SearchService> search) {
        this.commands = new CliCommands(registry, policies, store, status, runner, () -> CodeSearchException.resolve(embedder::getObject), () -> CodeSearchException.resolve(indexer::getObject),
                () -> CodeSearchException.resolve(search::getObject));
    }

    @Override
    public void run(ApplicationArguments args) {
        // propriedades de configuração (--code-search.*, --server.*) não são argumentos do comando
        List<String> argv = java.util.Arrays.stream(args.getSourceArgs())
                .filter(a -> !a.startsWith("--code-search.") && !a.startsWith("--server.")).toList();
        if (argv.isEmpty() || argv.get(0).startsWith("--")) {
            return; // sem comando (ex.: apenas --server.port), o servidor web cuida do resto
        }
        // UTF-8 sempre: o padrão do console (ASCII no Linux sem locale, cp1252 no Windows) estraga acentos e o JSON do MCP
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        exitCode = commands.run(argv, System.in, out, err);
        out.flush();
        err.flush();
    }

    public int exitCode() {
        return exitCode;
    }
}
