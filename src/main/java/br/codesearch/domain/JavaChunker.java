package br.codesearch.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chunking de código Java: um chunk por método/construtor, janelas quando não cabe. Porta direta de {@code chunker.py}.
 *
 * <p>O scanner mascara comentários e literais, acompanha chaves e reconhece tipos (class, interface, enum, record) e seus
 * membros. Não é um parser Java completo: é o suficiente para delimitar símbolos.
 */
public final class JavaChunker {

    public static final String CHUNKER_VERSION = "1";
    static final int MAX_LINES = 80;
    static final int MAX_CHARS = 6000;
    static final int OVERLAP = 10;

    private static final int FLAGS = Pattern.UNICODE_CHARACTER_CLASS;
    private static final Set<String> CONTROL =
            Set.of("if", "for", "while", "switch", "catch", "synchronized", "try", "do", "else", "return", "new", "super", "this");
    private static final Pattern TYPE_RE =
            Pattern.compile("(?<![\\w$.])(class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)", FLAGS);
    private static final Pattern ANNOT_RE = Pattern.compile("@(?!interface\\b)[\\w.$]+", FLAGS);
    private static final Pattern IDENT_END_RE = Pattern.compile("([A-Za-z_$][\\w$]*)\\z", FLAGS);
    private static final Pattern AT_INTERFACE_RE = Pattern.compile("@interface\\s+([A-Za-z_$][\\w$]*)", FLAGS);
    private static final List<String> DOC_PREFIXES = List.of("/*", "*", "//", "@");
    private static final Set<String> BRACE_ONLY = Set.of("{", "}", "};", "});");

    private JavaChunker() {}

    private record Symbol(String kind, String name, List<String> path, int start, int end) {}

    private record Window(int start, int end) {}

    /** Troca comentários, strings e chars por espaços, preservando offsets e quebras de linha. */
    static String maskJava(String src) {
        char[] out = src.toCharArray();
        int n = src.length();
        int i = 0;
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && src.startsWith("//", i)) {
                int j = src.indexOf('\n', i);
                j = j < 0 ? n : j;
                blank(out, i, j, n);
                i = j;
            } else if (c == '/' && src.startsWith("/*", i)) {
                int j = src.indexOf("*/", i + 2);
                j = j < 0 ? n : j + 2;
                blank(out, i, j, n);
                i = j;
            } else if (src.startsWith("\"\"\"", i)) {
                int j = i + 3;
                while (j < n && !src.startsWith("\"\"\"", j)) {
                    j += src.charAt(j) == '\\' ? 2 : 1;
                }
                blank(out, i, j + 3, n);
                i = j + 3;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && src.charAt(j) != c && src.charAt(j) != '\n') {
                    j += src.charAt(j) == '\\' ? 2 : 1;
                }
                blank(out, i, j + 1, n);
                i = j + 1;
            } else {
                i++;
            }
        }
        return new String(out);
    }

    private static void blank(char[] out, int a, int b, int n) {
        for (int k = a; k < Math.min(b, n); k++) {
            if (out[k] != '\n') {
                out[k] = ' ';
            }
        }
    }

    private static Map<Integer, Integer> matchBraces(String masked) {
        Map<Integer, Integer> pairs = new HashMap<>();
        List<Integer> stack = new ArrayList<>();
        for (int i = 0; i < masked.length(); i++) {
            char c = masked.charAt(i);
            if (c == '{') {
                stack.add(i);
            } else if (c == '}' && !stack.isEmpty()) {
                pairs.put(stack.remove(stack.size() - 1), i);
            }
        }
        for (int i : stack) { // chaves sem fecho: vão até o fim do texto
            pairs.put(i, masked.length() - 1);
        }
        return pairs;
    }

    private static String stripAnnotations(String head) {
        StringBuilder out = new StringBuilder();
        int pos = 0;
        while (true) {
            Matcher m = ANNOT_RE.matcher(head);
            if (!m.find(pos)) {
                out.append(head, pos, head.length());
                break;
            }
            out.append(head, pos, m.start());
            int j = m.end();
            int k = j;
            while (k < head.length() && Py.isSpace(head.charAt(k))) {
                k++;
            }
            if (k < head.length() && head.charAt(k) == '(') {
                int depth = 0;
                while (k < head.length()) {
                    if (head.charAt(k) == '(') {
                        depth++;
                    } else if (head.charAt(k) == ')') {
                        depth--;
                        if (depth == 0) {
                            k++;
                            break;
                        }
                    }
                    k++;
                }
                j = k;
            }
            pos = j;
        }
        return out.toString();
    }

    private static boolean hasTopLevelAssign(String head) {
        int depth = 0;
        for (int i = 0; i < head.length(); i++) {
            char c = head.charAt(i);
            if (c == '(' || c == '<' || c == '[') {
                depth++;
            } else if (c == ')' || c == '>' || c == ']') {
                depth = Math.max(0, depth - 1);
            } else if (c == '=' && depth == 0) {
                if (i + 1 >= head.length() || head.charAt(i + 1) != '=') {
                    return true;
                }
            }
        }
        return false;
    }

    private static String[] typeDecl(String head) {
        String h = stripAnnotations(head);
        if (hasTopLevelAssign(h)) {
            return null;
        }
        Matcher m = TYPE_RE.matcher(h);
        if (m.find()) {
            return new String[] {m.group(1), m.group(2)};
        }
        Matcher am = AT_INTERFACE_RE.matcher(head);
        return am.find() ? new String[] {"interface", am.group(1)} : null;
    }

    private static String methodName(String head, String cls) {
        String h = Py.strip(stripAnnotations(head));
        if (h.isEmpty() || hasTopLevelAssign(h)) {
            return null;
        }
        int idx = h.indexOf('(');
        if (idx < 0) { // construtor compacto de record: "public Foo {"
            Matcher m = IDENT_END_RE.matcher(h);
            return m.find() && m.group(1).equals(cls) ? m.group(1) : null;
        }
        Matcher m = IDENT_END_RE.matcher(Py.rstrip(h.substring(0, idx)));
        if (!m.find() || CONTROL.contains(m.group(1))) {
            return null;
        }
        String before = Py.rstrip(h.substring(0, m.start()));
        if (before.endsWith("new")) {
            return null;
        }
        return m.group(1);
    }

    private static List<Symbol> parseSymbols(String masked) {
        Map<Integer, Integer> pairs = matchBraces(masked);
        List<Symbol> symbols = new ArrayList<>();
        walk(masked, pairs, symbols, 0, masked.length(), List.of(), "file", "");
        return symbols;
    }

    private static int firstCode(String masked, int a, int b) {
        int k = a;
        while (k < b && Py.isSpace(masked.charAt(k))) {
            k++;
        }
        return k;
    }

    private static List<String> append(List<String> path, String name) {
        List<String> next = new ArrayList<>(path);
        next.add(name);
        return next;
    }

    private static void walk(
            String masked, Map<Integer, Integer> pairs, List<Symbol> symbols,
            int start, int end, List<String> path, String parentKind, String cls) {
        int i = start;
        int mstart = start;
        int paren = 0;
        boolean constants = parentKind.equals("enum"); // região de constantes antes do primeiro ';'
        while (i < end) {
            char c = masked.charAt(i);
            if (c == '(') {
                paren++;
            } else if (c == ')') {
                paren = Math.max(0, paren - 1);
            } else if (paren == 0 && c == ';') {
                if (constants) {
                    constants = false;
                } else if (parentKind.equals("interface")) {
                    String head = masked.substring(mstart, i);
                    String name = methodName(head, cls);
                    if (name != null && head.contains("(")) {
                        symbols.add(new Symbol("method", name, path, firstCode(masked, mstart, i), i));
                    }
                }
                mstart = i + 1;
            } else if (paren == 0 && c == '{') {
                int close = pairs.getOrDefault(i, end);
                String head = masked.substring(mstart, i);
                String[] decl = typeDecl(head);
                if (decl != null) {
                    List<String> npath = append(path, decl[1]);
                    symbols.add(new Symbol("type", decl[1], npath, firstCode(masked, mstart, i), close));
                    walk(masked, pairs, symbols, i + 1, close, npath, decl[0], decl[1]);
                } else if (!constants) {
                    String name = methodName(head, cls);
                    if (name != null) {
                        symbols.add(new Symbol("method", name, path, firstCode(masked, mstart, i), close));
                    }
                }
                i = close;
                mstart = close + 1;
            }
            i++;
        }
    }

    /** Janelas 1-based [s, e] com no máximo MAX_LINES linhas e MAX_CHARS caracteres, com sobreposição. */
    static List<Window> splitWindows(List<String> lines, int first, int last) {
        List<Window> out = new ArrayList<>();
        int s = first;
        while (s <= last) {
            int e = s;
            int chars = Py.len(lines.get(s - 1)) + 1;
            while (e < last && (e - s + 1) < MAX_LINES && chars + Py.len(lines.get(e)) + 1 <= MAX_CHARS) {
                e++;
                chars += Py.len(lines.get(e - 1)) + 1;
            }
            out.add(new Window(s, e));
            if (e >= last) {
                break;
            }
            s = Math.max(s + 1, e - OVERLAP + 1);
        }
        return out;
    }

    /** Sobe a linha inicial para incluir Javadoc/comentários colados no símbolo. */
    private static int withDocComment(List<String> lines, int line) {
        int start = line;
        while (start > 1 && line - start < 60) {
            String prev = Py.strip(lines.get(start - 2));
            boolean doc = !prev.isEmpty()
                    && (DOC_PREFIXES.stream().anyMatch(prev::startsWith) || prev.endsWith("*/"));
            if (doc) {
                start--;
            } else {
                break;
            }
        }
        return start;
    }

    private static List<String> pieces(String text) {
        if (Py.len(text) <= MAX_CHARS) {
            return List.of(text);
        }
        List<String> out = new ArrayList<>(); // linha única gigante
        int total = Py.len(text);
        for (int i = 0; i < total; i += MAX_CHARS) {
            int a = text.offsetByCodePoints(0, i);
            int b = text.offsetByCodePoints(0, Math.min(total, i + MAX_CHARS));
            out.add(text.substring(a, b));
        }
        return out;
    }

    /** Quebra um arquivo Java em chunks por símbolo. {@code source} é o caminho relativo ao repo. */
    public static List<ChunkDraft> chunkJava(String source, String rawText) {
        String text = rawText.replace("\r\n", "\n").replace("\r", "\n");
        List<String> lines = new ArrayList<>(Arrays.asList(text.split("\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        if (lines.isEmpty()) {
            return List.of();
        }
        String masked = maskJava(text);
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }

        List<Symbol> symbols = parseSymbols(masked);
        int slash = source.lastIndexOf('/');
        String name = slash < 0 ? source : source.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name; // PurePosixPath.stem
        List<Symbol> types = new ArrayList<>();
        List<Symbol> methods = new ArrayList<>();
        for (Symbol s : symbols) {
            (s.kind().equals("type") ? types : methods).add(s);
        }
        types.sort(Comparator.comparingInt(Symbol::start));
        methods.sort(Comparator.comparingInt(Symbol::start));

        List<ChunkDraft> drafts = new ArrayList<>();
        boolean[] covered = new boolean[lines.size() + 2];

        for (Symbol m : methods) {
            int first = withDocComment(lines, lineOf(starts, m.start()));
            int last = Math.min(lineOf(starts, m.end()), lines.size());
            String path = String.join(".", m.path());
            String cls = m.path().isEmpty() ? stem : m.path().get(m.path().size() - 1);
            String symbol = m.path().isEmpty() ? m.name() : path + "." + m.name();
            emit(drafts, source, lines, first, last, symbol, path, cls);
            for (int ln = first; ln <= last; ln++) {
                covered[ln] = true;
            }
        }

        int ln = 1;
        while (ln <= lines.size()) {
            if (covered[ln]) {
                ln++;
                continue;
            }
            int end = ln;
            while (end + 1 <= lines.size() && !covered[end + 1]) {
                end++;
            }
            StringBuilder meaningful = new StringBuilder();
            for (int k = ln; k <= end; k++) {
                String stripped = Py.strip(lines.get(k - 1));
                if (!BRACE_ONLY.contains(stripped)) {
                    meaningful.append(stripped);
                }
            }
            if (Py.len(meaningful.toString()) >= 12) {
                int firstCodeLine = ln;
                while (Py.strip(lines.get(firstCodeLine - 1)).isEmpty()) {
                    firstCodeLine++;
                }
                int lastCodeLine = end;
                while (Py.strip(lines.get(lastCodeLine - 1)).isEmpty()) {
                    lastCodeLine--;
                }
                List<String> path = enclosing(types, starts, firstCodeLine);
                String symbol = path.isEmpty() ? stem : String.join(".", path);
                emit(drafts, source, lines, firstCodeLine, lastCodeLine, symbol, "",
                        path.isEmpty() ? stem : path.get(path.size() - 1));
            }
            ln = end + 1;
        }

        drafts.sort(Comparator.comparingInt(ChunkDraft::startLine)
                .thenComparingInt(ChunkDraft::endLine)
                .thenComparing(ChunkDraft::symbol));
        return drafts;
    }

    /** bisect_right(starts, offset): número de inícios de linha menores ou iguais ao offset. */
    private static int lineOf(List<Integer> starts, int offset) {
        int lo = 0;
        int hi = starts.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (offset < starts.get(mid)) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return lo;
    }

    private static List<String> enclosing(List<Symbol> types, List<Integer> starts, int line) {
        List<String> best = List.of();
        for (Symbol t : types) {
            if (lineOf(starts, t.start()) <= line && line <= lineOf(starts, t.end()) && t.path().size() >= best.size()) {
                best = t.path();
            }
        }
        return best;
    }

    private static void emit(
            List<ChunkDraft> drafts, String source, List<String> lines, int first, int last,
            String symbol, String parent, String cls) {
        for (Window w : splitWindows(lines, first, last)) {
            StringBuilder body = new StringBuilder();
            for (int k = w.start(); k <= w.end(); k++) {
                if (k > w.start()) {
                    body.append('\n');
                }
                String line = lines.get(k - 1);
                int e = line.length();
                while (e > 0 && line.charAt(e - 1) == '\r') {
                    e--;
                }
                body.append(line, 0, e);
            }
            if (Py.strip(body.toString()).isEmpty()) {
                continue;
            }
            for (String piece : pieces(body.toString())) {
                drafts.add(new ChunkDraft(source, symbol, parent, cls, w.start(), w.end(), piece));
            }
        }
    }
}
