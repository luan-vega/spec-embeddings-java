package br.codesearch.config;

import java.nio.file.Path;

/** Caminhos padrão. Tudo local; nenhuma configuração aponta para fora de loopback. */
public record DataPaths(Path home) {

    public static DataPaths of(String configured) {
        return new DataPaths(configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), ".workflows", "code-search") : Path.of(configured));
    }

    public Path registry() {
        return home.resolve("repositories.json");
    }

    public Path policy() {
        return home.resolve("exclusion-policy.json");
    }

    public Path cache() {
        return home.resolve("cache");
    }

    public Path index() {
        return home.resolve("index");
    }
}
