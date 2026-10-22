package br.codesearch.adapter.in;

import static org.assertj.core.api.Assertions.assertThat;

import br.codesearch.adapter.in.cli.CliCommands;
import br.codesearch.application.ProviderInfo;
import br.codesearch.application.port.ProviderCatalog;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ProvidersCommandTest {

    @TempDir
    Path tmp;

    static final ProviderCatalog CATALOG = new ProviderCatalog() {
        @Override
        public List<ProviderInfo> providers() {
            return List.of(
                    new ProviderInfo("embedder", "ollama", false, true, "nomic", "http://127.0.0.1:11434", List.of()),
                    new ProviderInfo("embedder", "openai", true, false, "text-embedding-3-small", "https://api.openai.com/v1",
                            List.of("OPENAI_API_KEY")));
        }

        @Override
        public boolean allowRemote() {
            return false;
        }
    };

    String run(String... args) {
        TestApp app = new TestApp(tmp.resolve("home"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CliCommands cli = app.cliWith(CATALOG);
        cli.run(List.of(args), System.in, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream()));
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void textListsProvidersWhatIsMissingAndTheRemoteGate() {
        String text = run("providers");
        assertThat(text).contains("externos autorizados: não").contains("--allow-remote");
        assertThat(text).contains("embedder  ollama  local   * pronto").contains("embedder  openai  EXTERNO   falta OPENAI_API_KEY");
    }

    @Test
    void jsonNeverCarriesKeyValues() throws Exception {
        JsonNode json = JsonMapper.builder().build().readTree(run("providers", "--json"));
        assertThat(json.get("allow_remote").asBoolean()).isFalse();
        assertThat(json.get("providers")).hasSize(2);
        assertThat(json.get("providers").get(1).get("missing").get(0).asString()).isEqualTo("OPENAI_API_KEY");
        assertThat(json.get("providers").get(0).get("ready").asBoolean()).isTrue();
        assertThat(json.toString()).doesNotContain("sk-");
    }
}
