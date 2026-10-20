package br.codesearch;

import static org.assertj.core.api.Assertions.assertThat;

import br.codesearch.adapter.in.cli.CliRunner;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "code-search.home=${java.io.tmpdir}/code-search-context-test")
class CodeSearchApplicationTests {

    @Autowired
    CliRunner runner;

    @Test
    void contextLoads() {
    }

    private record Output(int code, String out, String err) {}

    /** Roda o comando como a linha de comando faz, no contexto real, com o Ollama sem modelo configurado. */
    private Output cli(String... args) {
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            runner.run(new DefaultApplicationArguments(args));
            return new Output(runner.exitCode(), out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }

    @Test
    void statusWorksWithoutOllamaAndReportsNoEmbedder() {
        Output status = cli("status", "--json");
        assertThat(status.code()).isZero();
        assertThat(status.out()).contains("\"embedder\" : null").contains("\"repos\" : [ ]");
    }

    @Test
    void searchWithoutAConfiguredModelFailsWithAStableCodeNotAnInternalError() {
        Output search = cli("search", "juros");
        assertThat(search.code()).isEqualTo(1);
        assertThat(search.err()).contains("[embedder_unavailable]").contains("CODE_SEARCH_OLLAMA_MODEL").doesNotContain("Exception");
    }

    @Test
    void accentedOutputIsUtf8() {
        Output usage = cli("-h");
        assertThat(usage.out()).contains("política de exclusão");
    }
}
