package br.codesearch.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/** Filtros de busca. {@code languages} nulo significa qualquer linguagem; {@code pathPrefix} são componentes. */
public record Filters(Set<String> languages, List<String> pathPrefix) {

    public static final Filters NONE = new Filters(null, List.of());

    public static Filters make(Collection<String> languages, String pathPrefix) {
        Set<String> langs = languages == null || languages.isEmpty() ? null : Set.copyOf(languages);
        List<String> parts = new ArrayList<>();
        if (pathPrefix != null && !pathPrefix.isEmpty()) {
            String normalized = pathPrefix.replace("\\", "/");
            if (isAbsolute(normalized)) {
                throw new CodeSearchException("invalid_request", "path_prefix must be relative");
            }
            parts = Paths.components(normalized);
            if (parts.contains("..")) {
                throw new CodeSearchException("invalid_request", "path_prefix must not contain '..'");
            }
        }
        return new Filters(langs, List.copyOf(parts));
    }

    static boolean isAbsolute(String normalized) {
        return normalized.startsWith("/") || (normalized.length() > 1 && normalized.charAt(1) == ':');
    }
}
