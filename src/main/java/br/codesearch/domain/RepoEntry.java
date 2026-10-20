package br.codesearch.domain;

import java.util.List;

/** Repositório registrado. {@code path} é local e nunca é exposto pela API. */
public record RepoEntry(String repoId, String path, List<String> roots, boolean enabled, String ref) {

    public static final List<String> DEFAULT_ROOTS = List.of("src/main/java");

    public RepoEntry(String repoId, String path) {
        this(repoId, path, DEFAULT_ROOTS, true, "HEAD");
    }

    public static String normalizeRoot(String root) {
        String normalized = root.replace("\\", "/");
        if (Filters.isAbsolute(normalized)) {
            throw new CodeSearchException("invalid_request", "roots must be relative to the repository");
        }
        List<String> parts = Paths.components(normalized);
        if (parts.isEmpty() || parts.contains("..")) {
            throw new CodeSearchException("invalid_request", "invalid root");
        }
        return String.join("/", parts);
    }
}
