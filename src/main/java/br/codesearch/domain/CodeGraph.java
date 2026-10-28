package br.codesearch.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Grafo de código sobre os chunks já indexados, para o GraphRAG do ask: cada chunk é um nó, e as arestas dizem quem chama
 * quem, quem estende ou implementa quem (classes e métodos sobrescritos), quem usa qual tipo e de qual classe cada método é
 * membro, inclusive entre repositórios. As relações saem do texto dos chunks com o mesmo mascaramento do {@link JavaChunker} (comentários e strings
 * não contam): é uma aproximação por nomes, sem resolver imports nem sobrecarga, e chamadas ambíguas demais são descartadas
 * em vez de ligadas a tudo.
 *
 * <p>Os alvos das arestas são o primeiro chunk de cada símbolo (o começo do método ou o cabeçalho da classe).
 */
public final class CodeGraph {

    /** Máximo de alvos para uma mesma chamada ou tipo; acima disso o nome é comum demais para dizer algo. */
    static final int MAX_TARGETS = 4;
    /** Arestas seguidas a partir de cada nó durante a expansão, das mais fortes para as mais fracas. */
    static final int MAX_FANOUT = 12;
    /** Relações listadas entre os trechos de um contexto. */
    static final int MAX_LINKS = 40;

    private static final int FLAGS = Pattern.UNICODE_CHARACTER_CLASS;
    private static final Pattern CALL = Pattern.compile(
            "(?:([A-Za-z_$][\\w$]*)\\s*\\.\\s*)?(?<![\\w$])([a-z_$][\\w$]*)\\s*\\(", FLAGS);
    private static final Pattern TYPE_REF = Pattern.compile("(?<![\\w$])([A-Z][\\w$]*)", FLAGS);
    private static final Pattern VAR_DECL = Pattern.compile(
            "(?<![\\w$.])([A-Z][\\w$]*)(?:\\s*<[^;(){}=]*>)?(?:\\s*\\[\\s*])*\\s+([a-z_$][\\w$]*)\\s*[=;,):]", FLAGS);
    private static final Pattern TYPE_HEADER = Pattern.compile(
            "(?<![\\w$.])(?:class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)([^{]*)\\{", FLAGS);
    private static final Pattern SUPERTYPES = Pattern.compile("\\b(extends|implements)\\b([^{]*?)(?=\\bimplements\\b|$)", FLAGS);
    private static final Pattern QUALIFIED = Pattern.compile("[A-Za-z_$][\\w$]*(?:\\s*\\.\\s*[A-Za-z_$][\\w$]*)*", FLAGS);
    private static final Set<String> KEYWORDS = Set.of(
            "if", "for", "while", "switch", "catch", "synchronized", "try", "do", "else", "return", "new", "super", "this",
            "throw", "assert", "case", "yield", "instanceof", "var", "void", "int", "long", "double", "float", "boolean",
            "char", "byte", "short", "class", "default", "when", "record");

    /** Tipo de relação, com o peso de cada sentido na expansão e o texto usado no prompt. */
    public enum Relation {
        CALLS("called_by", "calls", "calls", 1.0, 0.8),
        INHERITS("supertype_of", "subtype_of", "extends or implements", 0.9, 0.7),
        REFERENCES("type_used_by", "uses", "uses type", 0.6, 0.5),
        MEMBER_OF("class_of", "member_of", "is a member of", 0.5, 0.3);

        /** Como o vizinho se relaciona com o nó de partida quando a aresta sai do nó de partida. */
        final String forwardVia;
        /** Idem quando a aresta chega ao nó de partida. */
        final String backwardVia;
        /** Verbo de "[a] verbo [b]" na lista de relações. */
        public final String verb;
        final double forwardWeight;
        final double backwardWeight;

        Relation(String forwardVia, String backwardVia, String verb, double forwardWeight, double backwardWeight) {
            this.forwardVia = forwardVia;
            this.backwardVia = backwardVia;
            this.verb = verb;
            this.forwardWeight = forwardWeight;
            this.backwardWeight = backwardWeight;
        }
    }

    /** Chunk como entra na montagem: o texto só serve para extrair as relações e não fica no grafo. */
    public record Input(
            String repo, int row, String source, String symbol, String parentSymbol, int startLine, int endLine, String text) {}

    /** Nó do grafo; {@code row} é a linha do chunk no segmento do repositório. */
    public record Node(String repo, int row, String source, String symbol, String parentSymbol, int startLine, int endLine) {

        String symbolKey() {
            return repo + "\u001f" + source + "\u001f" + symbol;
        }
    }

    /**
     * Vizinho achado pela expansão. {@code seed} é o índice (base 0) do trecho-semente que mais contribuiu e {@code via} diz
     * como o vizinho se liga a ele, do ponto de vista do vizinho: called_by (a semente chama este), calls (este chama a
     * semente), supertype_of, subtype_of, type_used_by, uses, class_of ou member_of; com {@code hops} 2 a ligação é indireta e {@code via} traz os dois passos ("called_by > called_by").
     */
    public record Neighbor(int node, Node info, double score, int seed, String via, int hops) {}

    /** Relação dirigida entre dois trechos de uma lista: {@code from} e {@code to} são índices nessa lista. */
    public record Link(int from, int to, Relation relation) {}

    private record Edge(int target, Relation relation, boolean forward) {

        double weight() {
            return forward ? relation.forwardWeight : relation.backwardWeight;
        }
    }

    private final List<Node> nodes;
    private final List<List<Edge>> adjacency;
    private final Map<String, Integer> byLocation;
    private final int edgeCount;

    private CodeGraph(List<Node> nodes, List<List<Edge>> adjacency, Map<String, Integer> byLocation, int edgeCount) {
        this.nodes = nodes;
        this.adjacency = adjacency;
        this.byLocation = byLocation;
        this.edgeCount = edgeCount;
    }

    public int size() {
        return nodes.size();
    }

    /** Arestas dirigidas (cada relação conta uma vez, embora a expansão ande nos dois sentidos). */
    public int edgeCount() {
        return edgeCount;
    }

    public Node node(int id) {
        return nodes.get(id);
    }

    /** Nó de um chunk pela posição, como devolvido pela busca. */
    public OptionalInt find(String repo, String source, String symbol, int startLine, int endLine) {
        Integer id = byLocation.get(location(repo, source, symbol, startLine, endLine));
        return id == null ? OptionalInt.empty() : OptionalInt.of(id);
    }

    private static String location(String repo, String source, String symbol, int startLine, int endLine) {
        return repo + "\u001f" + source + "\u001f" + symbol + "\u001f" + startLine + "\u001f" + endLine;
    }

    private static String last(String dotted) {
        int dot = dotted.lastIndexOf('.');
        return dot < 0 ? dotted : dotted.substring(dot + 1);
    }

    private static boolean isMethod(Input in) {
        return !in.parentSymbol().isEmpty();
    }

    /** Classe dona do chunk: a do método, ou a própria classe num trecho fora de métodos. */
    private static String owner(Input in) {
        return last(isMethod(in) ? in.parentSymbol() : in.symbol());
    }

    /** Monta o grafo. A ordem das entradas define os ids dos nós. */
    public static CodeGraph build(List<Input> inputs) {
        int n = inputs.size();
        List<Node> nodes = new ArrayList<>(n);
        Map<String, Integer> byLocation = new HashMap<>();
        Map<String, Integer> canonical = new HashMap<>(); // símbolo → primeiro chunk (menor linha)
        for (int i = 0; i < n; i++) {
            Input in = inputs.get(i);
            Node node = new Node(in.repo(), in.row(), in.source(), in.symbol(), in.parentSymbol(), in.startLine(), in.endLine());
            nodes.add(node);
            byLocation.putIfAbsent(location(in.repo(), in.source(), in.symbol(), in.startLine(), in.endLine()), i);
            canonical.merge(node.symbolKey(), i, (a, b) -> inputs.get(b).startLine() < inputs.get(a).startLine() ? b : a);
        }

        Map<String, List<Integer>> methodsByName = new HashMap<>();
        Map<String, List<Integer>> typesByName = new HashMap<>();
        Map<String, Integer> typeByPath = new HashMap<>(); // repo, arquivo e caminho da classe → cabeçalho
        Map<String, Set<String>> supertypes = new HashMap<>(); // nome simples → supertipos (nomes simples)
        Map<String, Map<String, String>> fieldTypes = new HashMap<>(); // nome simples da classe → campo → tipo
        String[] masked = new String[n];
        for (int i = 0; i < n; i++) {
            Input in = inputs.get(i);
            masked[i] = JavaChunker.maskJava(in.text());
            if (canonical.get(nodes.get(i).symbolKey()) == i) {
                if (isMethod(in)) {
                    methodsByName.computeIfAbsent(last(in.symbol()), k -> new ArrayList<>()).add(i);
                } else {
                    typesByName.computeIfAbsent(owner(in), k -> new ArrayList<>()).add(i);
                    typeByPath.put(in.repo() + "\u001f" + in.source() + "\u001f" + in.symbol(), i);
                }
            }
            if (!isMethod(in)) {
                Map<String, String> fields = fieldTypes.computeIfAbsent(owner(in), k -> new HashMap<>());
                fields.putAll(variableTypes(masked[i]));
                Matcher h = TYPE_HEADER.matcher(masked[i]);
                while (h.find()) {
                    supertypes.computeIfAbsent(h.group(1), k -> new LinkedHashSet<>()).addAll(parseSupertypes(h.group(2)));
                }
            }
        }
        Map<String, Set<String>> subtypes = new HashMap<>();
        supertypes.forEach((type, supers) -> supers.forEach(s -> subtypes.computeIfAbsent(s, k -> new HashSet<>()).add(type)));

        List<Set<Edge>> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new LinkedHashSet<>());
        }
        for (int i = 0; i < n; i++) {
            Input in = inputs.get(i);
            String own = owner(in);
            Set<Edge> edges = out.get(i);
            Set<String> refs = new LinkedHashSet<>();
            Matcher t = TYPE_REF.matcher(masked[i]);
            while (t.find()) {
                refs.add(t.group(1));
            }

            if (isMethod(in)) {
                Integer cls = typeByPath.get(in.repo() + "\u001f" + in.source() + "\u001f" + in.parentSymbol());
                if (cls != null) {
                    edges.add(new Edge(cls, Relation.MEMBER_OF, true));
                }
                Map<String, String> vars = new HashMap<>(fieldTypes.getOrDefault(own, Map.of()));
                vars.putAll(variableTypes(masked[i]));
                String self = last(in.symbol());
                // método que sobrescreve ou implementa o de um supertipo
                Set<String> supers = supertypes.getOrDefault(own, Set.of());
                for (int target : methodsByName.getOrDefault(self, List.of())) {
                    if (supers.contains(last(nodes.get(target).parentSymbol()))) {
                        edges.add(new Edge(target, Relation.INHERITS, true));
                    }
                }
                int body = masked[i].indexOf('{') < 0 ? masked[i].length() : masked[i].indexOf('{');
                Matcher c = CALL.matcher(masked[i]);
                while (c.find()) {
                    String name = c.group(2);
                    if (KEYWORDS.contains(name) || (name.equals(self) && c.start(2) < body)) {
                        continue; // a própria declaração não é chamada
                    }
                    if (c.group(1) == null && chained(masked[i], c.start(2))) {
                        continue; // a.b().c(): o dono de c é desconhecido e ligar por nome erra demais
                    }
                    for (int target : resolveCall(name, c.group(1), own, vars, refs, nodes, methodsByName, supertypes,
                            subtypes)) {
                        if (!nodes.get(target).symbolKey().equals(nodes.get(i).symbolKey())) {
                            edges.add(new Edge(target, Relation.CALLS, true));
                        }
                    }
                }
            } else {
                Matcher h = TYPE_HEADER.matcher(masked[i]);
                while (h.find()) {
                    for (String s : parseSupertypes(h.group(2))) {
                        for (int target : limited(typesByName.get(s))) {
                            edges.add(new Edge(target, Relation.INHERITS, true));
                        }
                    }
                }
            }
            for (String ref : refs) {
                if (ref.equals(own)) {
                    continue;
                }
                for (int target : limited(typesByName.get(ref))) {
                    edges.add(new Edge(target, Relation.REFERENCES, true));
                }
            }
        }

        // Cada aresta também vale no sentido contrário (quem chama, quem implementa, quem usa).
        List<List<Edge>> adjacency = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            adjacency.add(new ArrayList<>(out.get(i)));
        }
        int edgeCount = 0;
        for (int i = 0; i < n; i++) {
            for (Edge e : out.get(i)) {
                adjacency.get(e.target()).add(new Edge(i, e.relation(), false));
                edgeCount++;
            }
        }
        for (List<Edge> list : adjacency) {
            list.sort(Comparator.comparingDouble((Edge e) -> -e.weight()));
        }
        return new CodeGraph(List.copyOf(nodes), adjacency, byLocation, edgeCount);
    }

    private static boolean chained(String masked, int at) {
        int k = at - 1;
        while (k >= 0 && Character.isWhitespace(masked.charAt(k))) {
            k--;
        }
        return k >= 0 && masked.charAt(k) == '.';
    }

    private static List<Integer> limited(List<Integer> targets) {
        return targets == null || targets.size() > MAX_TARGETS ? List.of() : targets;
    }

    /** Tipos declarados de variáveis, parâmetros e campos: {@code Pagamento pagamento =} vira pagamento → Pagamento. */
    private static Map<String, String> variableTypes(String masked) {
        Map<String, String> vars = new HashMap<>();
        Matcher m = VAR_DECL.matcher(masked);
        while (m.find()) {
            vars.putIfAbsent(m.group(2), m.group(1));
        }
        return vars;
    }

    /** Nomes simples depois de extends e implements, sem parâmetros de tipo. */
    static List<String> parseSupertypes(String header) {
        String h = header;
        String previous;
        do { // tira <...> de dentro para fora
            previous = h;
            h = h.replaceAll("<[^<>]*>", " ");
        } while (!h.equals(previous));
        h = h.replaceAll("\\([^)]*\\)", " "); // componentes de record
        List<String> out = new ArrayList<>();
        Matcher m = SUPERTYPES.matcher(h);
        while (m.find()) {
            for (String part : m.group(2).split(",")) {
                Matcher q = QUALIFIED.matcher(part.strip());
                if (q.lookingAt()) {
                    out.add(last(q.group().replaceAll("\\s", "")));
                }
            }
        }
        return out;
    }

    /**
     * Alvos de uma chamada {@code qualifier.name(...)}. O dono do método vem do qualificador (classe, variável com tipo
     * conhecido ou this), ou é a própria classe quando não há qualificador; a herança vale nos dois sentidos, então uma chamada
     * na interface também chega às implementações (se forem muitas, só ao método da interface). Sem dono conhecido, só vale um método de classe citada no próprio trecho.
     */
    private static List<Integer> resolveCall(
            String name, String qualifier, String own, Map<String, String> vars, Set<String> refs, List<Node> nodes,
            Map<String, List<Integer>> methodsByName, Map<String, Set<String>> supertypes, Map<String, Set<String>> subtypes) {
        List<Integer> candidates = methodsByName.get(name);
        if (candidates == null) {
            return List.of();
        }
        String hint;
        if (qualifier == null || qualifier.equals("this") || qualifier.equals("super")) {
            hint = own;
        } else if (Character.isUpperCase(qualifier.charAt(0))) {
            hint = qualifier;
        } else {
            hint = vars.get(qualifier);
        }
        Set<String> owners = new HashSet<>();
        if (hint != null) {
            owners.add(hint);
            owners.addAll(supertypes.getOrDefault(hint, Set.of()));
            owners.addAll(subtypes.getOrDefault(hint, Set.of()));
        } else {
            owners.addAll(refs);
        }
        List<Integer> out = new ArrayList<>();
        List<Integer> exact = new ArrayList<>();
        for (int id : candidates) {
            String owner = last(nodes.get(id).parentSymbol());
            if (owners.contains(owner)) {
                out.add(id);
            }
            if (owner.equals(hint)) {
                exact.add(id);
            }
        }
        if (out.size() <= MAX_TARGETS) {
            return out;
        }
        // interface com muitas implementações: fica só o método do tipo declarado
        return exact.size() <= MAX_TARGETS ? exact : List.of();
    }

    /**
     * Vizinhos das sementes até {@code hops} passos (1 ou 2), do mais para o menos ligado. O score soma as contribuições:
     * peso da semente (1/posição) × peso da relação, pela metade no segundo passo; um nó ligado a várias sementes sobe.
     * Ficam de fora as próprias sementes, outros pedaços do mesmo símbolo de uma semente e o que {@code allowed} recusar.
     */
    public List<Neighbor> expand(List<Integer> seeds, int hops, int limit, Predicate<Node> allowed) {
        if (seeds.isEmpty() || limit <= 0 || hops <= 0) {
            return List.of();
        }
        Set<String> seedSymbols = new HashSet<>();
        for (int s : seeds) {
            seedSymbols.add(nodes.get(s).symbolKey());
        }
        Map<Integer, double[]> score = new LinkedHashMap<>(); // nó → {score, melhor contribuição, semente, hops}
        Map<Integer, String> via = new HashMap<>();
        Map<Integer, Double> frontier = new LinkedHashMap<>();
        Map<Integer, Integer> origin = new HashMap<>();
        for (int i = 0; i < seeds.size(); i++) {
            frontier.merge(seeds.get(i), 1.0 / (i + 1), Math::max);
            origin.putIfAbsent(seeds.get(i), i);
        }
        Map<Integer, String> firstVia = new HashMap<>();
        for (int hop = 1; hop <= Math.min(hops, 2); hop++) {
            Map<Integer, Double> next = new LinkedHashMap<>();
            Map<Integer, Integer> nextOrigin = new HashMap<>();
            for (Map.Entry<Integer, Double> f : frontier.entrySet()) {
                int from = f.getKey();
                int seed = origin.get(from);
                List<Edge> edges = adjacency.get(from);
                for (Edge e : edges.subList(0, Math.min(MAX_FANOUT, edges.size()))) {
                    Node target = nodes.get(e.target());
                    if (seedSymbols.contains(target.symbolKey()) || !allowed.test(target)) {
                        continue;
                    }
                    double contribution = f.getValue() * e.weight() * (hop == 1 ? 1.0 : 0.5);
                    String step = e.forward() ? e.relation().forwardVia : e.relation().backwardVia;
                    String relation = hop == 1 ? step : firstVia.getOrDefault(from, "related") + " > " + step;
                    double[] s = score.computeIfAbsent(e.target(), k -> new double[] {0, -1, -1, 0});
                    s[0] += contribution;
                    if (contribution > s[1]) {
                        s[1] = contribution;
                        s[2] = seed;
                        s[3] = hop;
                        via.put(e.target(), relation);
                    }
                    if (hop == 1) {
                        firstVia.putIfAbsent(e.target(), relation);
                    }
                    if (next.merge(e.target(), contribution, Math::max) == contribution) {
                        nextOrigin.put(e.target(), seed);
                    }
                }
            }
            frontier = next;
            origin = nextOrigin;
        }
        // Um símbolo só aparece uma vez: o pedaço com mais score representa os outros.
        Map<String, Integer> bestPerSymbol = new LinkedHashMap<>();
        for (Map.Entry<Integer, double[]> e : score.entrySet()) {
            bestPerSymbol.merge(nodes.get(e.getKey()).symbolKey(), e.getKey(),
                    (a, b) -> score.get(b)[0] > score.get(a)[0] ? b : a);
        }
        List<Integer> ranked = new ArrayList<>(bestPerSymbol.values());
        ranked.sort(Comparator.comparingDouble((Integer id) -> -score.get(id)[0]).thenComparingInt(id -> id));
        List<Neighbor> result = new ArrayList<>();
        for (int id : ranked.subList(0, Math.min(limit, ranked.size()))) {
            double[] s = score.get(id);
            result.add(new Neighbor(id, nodes.get(id), s[0], (int) s[2], via.get(id), (int) s[3]));
        }
        return result;
    }

    /**
     * Relações diretas entre os nós de uma lista (por exemplo, os trechos que o modelo vai ler). Um alvo vale para qualquer
     * pedaço do mesmo símbolo; {@code from} e {@code to} são posições na lista.
     */
    public List<Link> links(List<Integer> list) {
        Map<String, List<Integer>> positions = new HashMap<>();
        for (int i = 0; i < list.size(); i++) {
            positions.computeIfAbsent(nodes.get(list.get(i)).symbolKey(), k -> new ArrayList<>()).add(i);
        }
        Set<Link> out = new LinkedHashSet<>();
        for (int i = 0; i < list.size() && out.size() < MAX_LINKS; i++) {
            for (Edge e : adjacency.get(list.get(i))) {
                if (!e.forward()) {
                    continue;
                }
                List<Integer> targets = positions.getOrDefault(nodes.get(e.target()).symbolKey(), List.of());
                if (!targets.isEmpty() && targets.get(0) != i) {
                    out.add(new Link(i, targets.get(0), e.relation()));
                }
            }
        }
        return List.copyOf(out).subList(0, Math.min(MAX_LINKS, out.size()));
    }
}
