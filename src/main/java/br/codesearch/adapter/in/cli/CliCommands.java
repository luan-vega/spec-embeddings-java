package br.codesearch.adapter.in.cli;

import br.codesearch.adapter.in.Payloads;
import br.codesearch.adapter.in.mcp.McpServer;
import br.codesearch.application.BoundedRunner;
import br.codesearch.application.IndexReport;
import br.codesearch.application.IndexService;
import br.codesearch.application.SearchResponse;
import br.codesearch.application.SearchService;
import br.codesearch.application.StatusService;
import br.codesearch.application.port.Embedder;
import br.codesearch.application.port.PolicyStore;
import br.codesearch.application.port.RepoCatalog;
import br.codesearch.application.port.VectorStore;
import br.codesearch.domain.CodeSearchException;
import br.codesearch.domain.EmbeddingProfile;
import br.codesearch.domain.RepoEntry;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import tools.jackson.databind.json.JsonMapper;

/**
 * CLI: repo add|list|remove, policy init, ollama-probe, index, search, status, rollback, prune, mcp. Fala só com as
 * portas e serviços da application; {@code serve} é tratado na inicialização da aplicação.
 */
public final class CliCommands {

    public static final String USAGE = """
            uso: code-search <comando> [opções]
              repo add <id> <caminho> [--root R]...   registra um repositório Git
              repo list | repo remove <id>
              policy init [--force]                   grava a política de exclusão padrão
              ollama-probe                            mostra modelo, digest, dimensão e profile_id
              index [--repo ID]... [--force]          reindexa os repositórios habilitados
              search <consulta> [--mode hybrid|semantic|lexical] [--repo ID]... [--language L]...
                     [--path-prefix P] [--top-k N] [--json]
              status [--json]                         estado do índice e dos repositórios
              rollback                                volta para a geração anterior
              prune [--tmp-max-age SEGUNDOS]          apaga gerações e segmentos sem referência
              serve [--host H] [--port N]             HTTP local em 127.0.0.1
              mcp                                     servidor MCP por stdio (somente leitura)

            provedor, em qualquer comando (padrão: local, pelo ambiente):
              --embedder ollama|openai|gemini         openai e gemini são EXTERNOS: trechos e consultas saem da máquina
              --describer ollama|openai|claude|gemini descreve métodos em inglês antes de indexar; os demais são EXTERNOS
              --allow-remote                          autoriza provedor externo só neste comando
            """;

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Map<String, Set<String>> FLAGS = Map.of(
            "policy", Set.of("--force"), "index", Set.of("--force"), "search", Set.of("--json"), "status", Set.of("--json"));
    private static final Map<String, Set<String>> OPTIONS = Map.of(
            "repo", Set.of("--root"), "index", Set.of("--repo"),
            "search", Set.of("--mode", "--repo", "--language", "--path-prefix", "--top-k"), "prune", Set.of("--tmp-max-age"));

    private final RepoCatalog registry;
    private final PolicyStore policies;
    private final VectorStore store;
    private final StatusService status;
    private final BoundedRunner runner;
    private final Supplier<Embedder> embedder;
    private final Supplier<IndexService> indexer;
    private final Supplier<SearchService> search;

    public CliCommands(
            RepoCatalog registry, PolicyStore policies, VectorStore store, StatusService status, BoundedRunner runner,
            Supplier<Embedder> embedder, Supplier<IndexService> indexer, Supplier<SearchService> search) {
        this.registry = registry;
        this.policies = policies;
        this.store = store;
        this.status = status;
        this.runner = runner;
        this.embedder = embedder;
        this.indexer = indexer;
        this.search = search;
    }

    /** Argumentos já separados em posicionais, opções com valor (repetíveis) e flags. */
    private record Parsed(List<String> positional, Map<String, List<String>> options, Set<String> flags) {
        String one(String name, String fallback) {
            List<String> v = options.get(name);
            return v == null ? fallback : v.get(v.size() - 1);
        }

        List<String> all(String name) {
            return options.get(name);
        }
    }

    private static Parsed parse(String command, List<String> args) {
        Set<String> flagNames = FLAGS.getOrDefault(command, Set.of());
        Set<String> optionNames = OPTIONS.getOrDefault(command, Set.of());
        List<String> positional = new ArrayList<>();
        Map<String, List<String>> options = new HashMap<>();
        Set<String> flags = new HashSet<>();
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (flagNames.contains(a)) {
                flags.add(a);
            } else if (optionNames.contains(a)) {
                if (i + 1 >= args.size()) {
                    throw new CodeSearchException("invalid_request", "missing value for " + a);
                }
                options.computeIfAbsent(a, k -> new ArrayList<>()).add(args.get(++i));
            } else if (a.startsWith("--")) {
                throw new CodeSearchException("invalid_request", "unknown option " + a);
            } else {
                positional.add(a);
            }
        }
        return new Parsed(positional, options, flags);
    }

    private static void expect(Parsed p, int count, String what) {
        if (p.positional().size() != count) {
            throw new CodeSearchException("invalid_request", "expected " + what);
        }
    }

    /** Executa um comando e devolve o código de saída. Nunca vaza stack trace, caminho ou código. */
    public int run(List<String> argv, InputStream in, PrintStream out, PrintStream err) {
        if (argv.isEmpty() || argv.get(0).equals("--help") || argv.get(0).equals("-h")) {
            out.print(USAGE);
            return argv.isEmpty() ? 2 : 0;
        }
        try {
            return dispatch(argv.get(0), argv.subList(1, argv.size()), in, out);
        } catch (CodeSearchException e) {
            err.println("erro [" + e.code() + "]: " + e.getMessage());
            return 1;
        } catch (RuntimeException | IOException e) {
            err.println("erro [internal_error]: unexpected failure");
            return 1;
        }
    }

    private int dispatch(String command, List<String> rest, InputStream in, PrintStream out) throws IOException {
        Parsed p = parse(command, rest);
        switch (command) {
            case "repo" -> {
                return repo(p, out);
            }
            case "policy" -> {
                if (p.positional().size() != 1 || !p.positional().get(0).equals("init")) {
                    throw new CodeSearchException("invalid_request", "expected: policy init");
                }
                if (policies.exists() && !p.flags().contains("--force")) {
                    throw new CodeSearchException("invalid_request", "policy already exists; use --force to overwrite");
                }
                policies.writeDefault();
                out.println("política de exclusão padrão gravada");
                return 0;
            }
            case "ollama-probe" -> {
                EmbeddingProfile prof = embedder.get().profile();
                out.printf("modelo %s%ndigest %s%ndim %d%nmax_input_tokens %d%nprofile_id %s%n",
                        prof.model(), prof.digest(), prof.dim(), prof.maxInputTokens(), prof.profileId());
                return 0;
            }
            case "index" -> {
                return index(p, out);
            }
            case "search" -> {
                return search(p, out);
            }
            case "status" -> {
                Map<String, Object> s = status.status();
                if (p.flags().contains("--json")) {
                    out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(s));
                } else {
                    printStatus(s, out);
                }
                return 0;
            }
            case "rollback" -> {
                out.println("geração " + store.rollback() + " voltou a ser a ativa");
                return 0;
            }
            case "prune" -> {
                double age;
                try {
                    age = Double.parseDouble(p.one("--tmp-max-age", "3600"));
                } catch (NumberFormatException e) {
                    throw new CodeSearchException("invalid_request", "--tmp-max-age must be a number");
                }
                Map<String, Integer> r = store.prune(age);
                out.printf("removidos: %d gerações, %d segmentos (%d em uso, ficam para a próxima)%n",
                        r.get("generations"), r.get("segments"), r.get("skipped"));
                return 0;
            }
            case "mcp" -> {
                new McpServer(search, status, runner).serve(in, out);
                return 0;
            }
            case "serve" -> throw new CodeSearchException("invalid_request", "serve must be the first argument of the application");
            default -> throw new CodeSearchException("invalid_request", "unknown command");
        }
    }

    private int repo(Parsed p, PrintStream out) {
        String sub = p.positional().isEmpty() ? "" : p.positional().get(0);
        switch (sub) {
            case "add" -> {
                expect(p, 3, "repo add <id> <caminho>");
                List<String> roots = p.all("--root") == null ? RepoEntry.DEFAULT_ROOTS : p.all("--root");
                RepoEntry e = registry.add(p.positional().get(1), Path.of(p.positional().get(2)), roots);
                out.println("repositório " + e.repoId() + " registrado (roots: " + String.join(", ", e.roots()) + ")");
                return 0;
            }
            case "list" -> {
                for (RepoEntry e : registry.list()) {
                    out.println(e.repoId() + "\t" + (e.enabled() ? "habilitado" : "desabilitado") + "\t" + String.join(", ", e.roots()));
                }
                return 0;
            }
            case "remove" -> {
                expect(p, 2, "repo remove <id>");
                boolean removed = registry.remove(p.positional().get(1));
                out.println(removed ? "removido do registro (os snapshots não são apagados)" : "repositório desconhecido");
                return removed ? 0 : 1;
            }
            default -> throw new CodeSearchException("invalid_request", "expected: repo add|list|remove");
        }
    }

    private int index(Parsed p, PrintStream out) {
        expect(p, 0, "no positional arguments");
        IndexReport report = indexer.get().index(p.all("--repo"), p.flags().contains("--force"));
        if (report.status().equals("skipped")) {
            out.println("nada indexado: " + report.reason());
            return 1;
        }
        if (report.status().equals("unchanged")) {
            out.println("nada mudou; geração " + report.generationId() + " continua ativa");
            return 0;
        }
        for (IndexReport.RepoReport r : report.repos()) {
            String rev = r.revision.length() > 8 ? r.revision.substring(0, 8) : r.revision;
            if (r.unchanged) {
                out.println(r.repoId + ": sem mudanças (rev " + rev + ")");
            } else {
                out.printf("%s: %d chunks, %d embeddings novos, %d do cache, %d descartados por segredo, rev %s%n",
                        r.repoId, r.chunks, r.embeddingsNew, r.embeddingsCached, r.chunksSkippedSecret, rev);
            }
        }
        out.println("geração " + report.generationId() + " ativa");
        return 0;
    }

    private int search(Parsed p, PrintStream out) {
        expect(p, 1, "search <consulta>");
        int topK;
        try {
            topK = Integer.parseInt(p.one("--top-k", "10"));
        } catch (NumberFormatException e) {
            throw new CodeSearchException("invalid_request", "top_k out of range");
        }
        SearchService.Query query = new SearchService.Query(
                p.positional().get(0), p.one("--mode", "hybrid"), p.all("--repo"), p.all("--language"),
                p.one("--path-prefix", null), topK, null);
        SearchService service = search.get();
        SearchResponse response = runner.run(() -> service.search(query));
        if (p.flags().contains("--json")) {
            out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Payloads.searchResponse(response)));
            return 0;
        }
        out.println(response.status() + " · geração " + response.index().generation() + " · repos: "
                + String.join(", ", response.coverage().searched()));
        for (SearchResponse.Unavailable u : response.coverage().unavailable()) {
            out.println("  indisponível: " + u.repo() + " (" + u.reason() + ")");
        }
        int i = 1;
        for (SearchResponse.Result r : response.results()) {
            var c = r.chunk();
            String rev = c.repoRevision().length() > 8 ? c.repoRevision().substring(0, 8) : c.repoRevision();
            out.printf("%2d. %s:%s:%d-%d  %s  (score %.4f, rev %s, %s)%n", i++, c.repo(), c.source(), c.startLine(),
                    c.endLine(), c.symbol(), r.score(), rev, c.indexedAt());
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private static void printStatus(Map<String, Object> s, PrintStream out) {
        Map<String, Object> policy = (Map<String, Object>) s.get("policy");
        out.println("política: " + (Boolean.TRUE.equals(policy.get("valid")) ? "válida" : "inválida (" + policy.get("reason") + ")"));
        Map<String, Object> idx = (Map<String, Object>) s.get("index");
        out.println("geração ativa: " + (idx != null && idx.containsKey("generation") ? idx.get("generation") : "nenhuma"));
        if (idx != null && idx.get("previous_generation") != null) {
            out.println("geração anterior (rollback): " + idx.get("previous_generation"));
        }
        for (Map<String, Object> r : (List<Map<String, Object>>) s.get("repos")) {
            out.println(r.get("repo_id") + "\t" + (Boolean.TRUE.equals(r.get("indexed")) ? "indexado" : "sem índice") + "\t"
                    + r.get("chunks") + " chunks\t" + (r.get("repo_revision") == null ? "-" : r.get("repo_revision")));
        }
    }
}
