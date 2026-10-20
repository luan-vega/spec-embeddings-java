package br.codesearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ProviderOptionsTest {

    @Test
    void providerOptionsBecomeConfigurationAndLeaveTheCommandUntouched() {
        var split = CodeSearchApplication.splitProviderOptions(new String[] {
            "index", "--describer", "claude", "--force", "--allow-remote", "--repo", "a", "--embedder", "ollama"});
        assertThat(split.command()).containsExactly("index", "--force", "--repo", "a");
        assertThat(split.properties()).containsExactly(
                "--code-search.describer=claude", "--code-search.allow-remote=true", "--code-search.embedder=ollama");
    }

    @Test
    void commandsWithoutProviderOptionsPassThrough() {
        var split = CodeSearchApplication.splitProviderOptions(new String[] {"search", "juros", "--top-k", "3"});
        assertThat(split.command()).containsExactly("search", "juros", "--top-k", "3");
        assertThat(split.properties()).isEmpty();
    }

    @Test
    void anOptionWithoutValueIsAnError() {
        assertThatThrownBy(() -> CodeSearchApplication.splitProviderOptions(new String[] {"search", "x", "--embedder"}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--embedder");
    }
}
