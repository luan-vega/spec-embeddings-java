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

    static int start(String[] args) {
        SpringApplication app = new SpringApplication(CodeSearchApplication.class);
        List<String> passed = new ArrayList<>(List.of(args));
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
            passed = List.of("--server.address=" + host, "--server.port=" + port);
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
        try (ConfigurableApplicationContext ctx = app.run(args)) {
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
