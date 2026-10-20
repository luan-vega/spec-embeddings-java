package br.codesearch;

import br.codesearch.adapter.in.cli.CliCommands;
import br.codesearch.adapter.in.cli.CliRunner;
import br.codesearch.domain.Loopback;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Ponto de entrada único. {@code serve} sobe o HTTP em loopback; qualquer outro comando roda como CLI (ou MCP por stdio) no
 * mesmo contexto, sem servidor web e sem logs no stdout, que pertence ao protocolo e à saída do comando.
 */
@SpringBootApplication
public class CodeSearchApplication {

    public static void main(String[] args) {
        System.exit(start(args));
    }

    /** Argumentos do comando (sem as opções de provedor) e as propriedades Spring equivalentes. */
    record Split(List<String> command, List<String> properties) {}

    /**
     * Escolha de provedor por comando: {@code --embedder X}, {@code --describer X} e {@code --allow-remote} valem para qualquer
     * comando e viram {@code code-search.*}, que têm precedência sobre as variáveis de ambiente.
     */
    static Split splitProviderOptions(String[] args) {
        List<String> command = new ArrayList<>();
        List<String> properties = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--embedder" -> properties.add("--code-search.embedder=" + valueAfter(args, i++));
                case "--describer" -> properties.add("--code-search.describer=" + valueAfter(args, i++));
                case "--allow-remote" -> properties.add("--code-search.allow-remote=true");
                default -> command.add(args[i]);
            }
        }
        return new Split(command, properties);
    }

    private static String valueAfter(String[] args, int i) {
        if (i + 1 >= args.length) {
            throw new IllegalArgumentException("missing value for " + args[i]);
        }
        return args[i + 1];
    }

    static int start(String[] rawArgs) {
        Split split;
        try {
            split = splitProviderOptions(rawArgs);
        } catch (IllegalArgumentException e) {
            System.err.println("erro [invalid_request]: " + e.getMessage());
            return 1;
        }
        String[] args = split.command().toArray(String[]::new);
        SpringApplication app = new SpringApplication(CodeSearchApplication.class);
        List<String> passed;
        if (args.length > 0 && args[0].equals("serve")) {
            String host = "127.0.0.1";
            String port = "8765";
            for (int i = 1; i < args.length; i++) {
                if (args[i].equals("--host") && i + 1 < args.length) {
                    host = args[++i];
                } else if (args[i].equals("--port") && i + 1 < args.length) {
                    port = args[++i];
                } else {
                    System.err.println("erro [invalid_request]: unknown option " + args[i]);
                    return 1;
                }
            }
            if (!Loopback.isLoopback(host)) {
                System.err.println("erro [invalid_request]: the server only listens on loopback");
                return 1;
            }
            passed = new ArrayList<>(List.of("--server.address=" + host, "--server.port=" + port));
            passed.addAll(split.properties());
            ConfigurableApplicationContext ctx = app.run(passed.toArray(String[]::new));
            return waitForShutdown(ctx);
        }
        if (args.length == 0) {
            System.out.print(CliCommands.USAGE);
            return 2;
        }
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setLogStartupInfo(false);
        System.setProperty("logging.level.root", "OFF");
        passed = new ArrayList<>(split.command());
        passed.addAll(split.properties());
        try (ConfigurableApplicationContext ctx = app.run(passed.toArray(String[]::new))) {
            return ctx.getBean(CliRunner.class).exitCode();
        } catch (RuntimeException e) {
            System.err.println("erro [startup_failed]: the application could not start");
            return 1;
        }
    }

    private static int waitForShutdown(ConfigurableApplicationContext ctx) {
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        ctx.close();
        return 0;
    }
}
