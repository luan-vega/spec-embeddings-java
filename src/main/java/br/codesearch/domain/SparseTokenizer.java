package br.codesearch.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Encoder esparso determinístico para identificadores (camelCase e snake_case). Equivale a {@code sparse.py}. */
public final class SparseTokenizer {

    public static final String TOKENIZER_VERSION = "1";

    private static final Pattern IDENT = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    private static final Pattern PARTS = Pattern.compile("[A-Z]+(?=[A-Z][a-z])|[A-Z]?[a-z]+|[A-Z]+|[0-9]+");

    private SparseTokenizer() {}

    /** Identificador inteiro (minúsculo) + suas partes. {@code calcularJuros} vira calcularjuros, calcular, juros. */
    public static List<String> tokenize(String text) {
        List<String> terms = new ArrayList<>();
        Matcher identifiers = IDENT.matcher(text);
        while (identifiers.find()) {
            String ident = identifiers.group();
            List<String> parts = new ArrayList<>();
            Matcher pm = PARTS.matcher(ident);
            while (pm.find()) {
                parts.add(pm.group().toLowerCase(Locale.ROOT));
            }
            String whole = ident.replace("_", "").replace("$", "").toLowerCase(Locale.ROOT);
            if (whole.isEmpty()) {
                continue;
            }
            if (parts.size() != 1 || !parts.get(0).equals(whole)) {
                terms.add(whole);
            }
            for (String part : parts) {
                if (part.length() > 1 || Character.isDigit(part.charAt(0))) {
                    terms.add(part);
                }
            }
        }
        return terms;
    }

    /** Termo para frequência, na ordem da primeira ocorrência. */
    public static Map<String, Integer> encodeDocument(String text) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String term : tokenize(text)) {
            counts.merge(term, 1, Integer::sum);
        }
        return counts;
    }

    public static Map<String, Double> encodeQuery(String text) {
        Map<String, Double> weights = new LinkedHashMap<>();
        for (String term : tokenize(text)) {
            weights.put(term, 1.0);
        }
        return weights;
    }
}
