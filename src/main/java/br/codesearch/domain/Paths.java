package br.codesearch.domain;

import java.util.ArrayList;
import java.util.List;

/** Componentes de caminho posix, como PurePosixPath.parts sem a raiz: descarta vazios e ".". */
public final class Paths {

    private Paths() {}

    public static List<String> components(String normalized) {
        List<String> parts = new ArrayList<>();
        for (String p : normalized.split("/", -1)) {
            if (!p.isEmpty() && !p.equals(".")) {
                parts.add(p);
            }
        }
        return parts;
    }
}
