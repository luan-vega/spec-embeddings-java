package br.codesearch.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Política de exclusão versionada + pré-filtro heurístico de segredos. Porta de {@code policy.py}.
 *
 * <p>A política é validada antes de qualquer arquivo ser aberto. Ausente ou inválida significa "indexa nada". O
 * pertencimento de caminho usa componentes, nunca {@code startsWith}. O filtro de segredos é uma barreira heurística,
 * não um scanner dedicado.
 */
public final class ExclusionPolicy {

    private static final Set<String> KEYS = Set.of(
            "version", "include_extensions", "exclude_dirs", "exclude_file_globs", "max_file_bytes", "secret_patterns");

    public static final Map<String, Object> DEFAULT_POLICY = defaultPolicy();

    private final boolean valid;
    private final String reason;
    private final String versionId;
    private final Set<String> includeExtensions;
    private final Set<String> excludeDirs;
    private final List<Pattern> excludeFileGlobs;
    private final long maxFileBytes;
    private final List<Pattern> secretPatterns;

    private ExclusionPolicy(
            boolean valid, String reason, String versionId, Set<String> includeExtensions, Set<String> excludeDirs,
            List<Pattern> excludeFileGlobs, long maxFileBytes, List<Pattern> secretPatterns) {
        this.valid = valid;
        this.reason = reason;
        this.versionId = versionId;
        this.includeExtensions = includeExtensions;
        this.excludeDirs = excludeDirs;
        this.excludeFileGlobs = excludeFileGlobs;
        this.maxFileBytes = maxFileBytes;
        this.secretPatterns = secretPatterns;
    }

    private static Map<String, Object> defaultPolicy() {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("version", "1");
        policy.put("include_extensions", List.of(".java"));
        policy.put("exclude_dirs", List.of(
                ".git", ".svn", ".hg", ".idea", ".settings", ".gradle", ".mvn",
                "node_modules", "build", "target", "out", "bin", "dist", "generated-sources"));
        policy.put("exclude_file_globs", List.of(
                ".env*", "*.pem", "*.key", "*.p12", "*.pfx", "*.jks", "*.keystore",
                "*.crt", "*.cer", "*.der", "id_rsa*", "id_ed25519*", "*.secret"));
        policy.put("max_file_bytes", 1_000_000);
        policy.put("secret_patterns", List.of(
                "-----BEGIN [A-Z ]*PRIVATE KEY-----",
                "\\bAKIA[0-9A-Z]{16}\\b",
                "\\bgh[pousr]_[A-Za-z0-9]{30,}\\b",
                "\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.",
                "(?i)\\b(password|passwd|secret|api[_-]?key|access[_-]?token|auth[_-]?token)\\b\\s*[:=]\\s*[\\\"'][^\\\"'\\s]{6,}[\\\"']"));
        return Map.copyOf(policy);
    }

    public static ExclusionPolicy denyAll(String reason) {
        return new ExclusionPolicy(false, reason, "none", Set.of(), Set.of(), List.of(), 0, List.of());
    }

    public boolean valid() {
        return valid;
    }

    public String reason() {
        return reason;
    }

    public String versionId() {
        return versionId;
    }

    public long maxFileBytes() {
        return maxFileBytes;
    }

    /** Valida e compila a política. {@code data} é o JSON já lido (Map, List, String, Integer, Long). */
    public static ExclusionPolicy fromDict(Object data) {
        ExclusionPolicy invalid = denyAll("policy_invalid");
        if (!(data instanceof Map<?, ?> map) || !map.keySet().equals(KEYS)) {
            return invalid;
        }
        if (!(map.get("version") instanceof String version) || version.isEmpty()) {
            return invalid;
        }
        List<List<String>> lists = new ArrayList<>();
        for (String key : List.of("include_extensions", "exclude_dirs", "exclude_file_globs", "secret_patterns")) {
            List<String> items = stringList(map.get(key));
            if (items == null) {
                return invalid;
            }
            lists.add(items);
        }
        List<String> exts = lists.get(0);
        List<String> dirs = lists.get(1);
        List<String> globs = lists.get(2);
        List<String> pats = lists.get(3);
        if (exts.isEmpty() || !exts.stream().allMatch(e -> e.startsWith("."))) {
            return invalid;
        }
        if (dirs.stream().anyMatch(d -> d.contains("/") || d.contains("\\"))) {
            return invalid;
        }
        if (!(map.get("max_file_bytes") instanceof Number size)
                || !(size instanceof Integer || size instanceof Long)
                || size.longValue() <= 0) {
            return invalid;
        }
        List<Pattern> compiled = new ArrayList<>();
        try {
            for (String p : pats) {
                compiled.add(Pattern.compile(p));
            }
        } catch (PatternSyntaxException e) {
            return invalid;
        }
        String digest = CanonicalJson.sha256Hex(CanonicalJson.write(data)).substring(0, 8);
        List<Pattern> globPatterns = new ArrayList<>();
        for (String g : globs) {
            globPatterns.add(Pattern.compile(globToRegex(g.toLowerCase(Locale.ROOT)), Pattern.DOTALL));
        }
        return new ExclusionPolicy(
                true, "", version + "-" + digest,
                Set.copyOf(exts.stream().map(e -> e.toLowerCase(Locale.ROOT)).toList()),
                Set.copyOf(dirs), globPatterns, size.longValue(), compiled);
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> items)) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof String s) || s.isEmpty()) {
                return null;
            }
            out.add(s);
        }
        return out;
    }

    /** Equivalente a fnmatch.fnmatchcase: {@code *}, {@code ?} e {@code [seq]} / {@code [!seq]}. */
    static String globToRegex(String glob) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = glob.length();
        while (i < n) {
            char c = glob.charAt(i++);
            if (c == '*') {
                out.append(".*");
            } else if (c == '?') {
                out.append('.');
            } else if (c == '[') {
                int j = i;
                if (j < n && glob.charAt(j) == '!') {
                    j++;
                }
                if (j < n && glob.charAt(j) == ']') {
                    j++;
                }
                while (j < n && glob.charAt(j) != ']') {
                    j++;
                }
                if (j >= n) {
                    out.append("\\[");
                } else {
                    String stuff = glob.substring(i, j).replace("\\", "\\\\");
                    i = j + 1;
                    if (stuff.startsWith("!")) {
                        stuff = "^" + stuff.substring(1);
                    } else if (stuff.startsWith("^")) {
                        stuff = "\\" + stuff;
                    }
                    out.append('[').append(stuff).append(']');
                }
            } else {
                out.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return out.toString();
    }

    /** Decide só pelo caminho relativo (posix), antes de abrir o arquivo. */
    public boolean allows(String relPath) {
        if (!valid) {
            return false;
        }
        String normalized = relPath.replace("\\", "/");
        if (normalized.startsWith("/")) {
            return false;
        }
        List<String> parts = new ArrayList<>();
        for (String p : normalized.split("/", -1)) {
            if (!p.isEmpty() && !p.equals(".")) {
                parts.add(p);
            }
        }
        if (parts.isEmpty() || parts.contains("..")) {
            return false;
        }
        for (String dir : parts.subList(0, parts.size() - 1)) {
            if (excludeDirs.contains(dir)) {
                return false;
            }
        }
        String lowered = parts.get(parts.size() - 1).toLowerCase(Locale.ROOT);
        for (Pattern glob : excludeFileGlobs) {
            if (glob.matcher(lowered).matches()) {
                return false;
            }
        }
        return includeExtensions.contains(suffix(lowered));
    }

    /** PurePosixPath.suffix: a partir do último ponto, ignorando nomes que só têm ponto inicial ou final. */
    private static String suffix(String name) {
        int i = name.lastIndexOf('.');
        return i > 0 && i < name.length() - 1 ? name.substring(i) : "";
    }

    public boolean containsSecret(String text) {
        for (Pattern p : secretPatterns) {
            if (p.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }
}
