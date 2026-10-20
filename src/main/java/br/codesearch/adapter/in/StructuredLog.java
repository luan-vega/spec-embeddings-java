package br.codesearch.adapter.in;

import java.io.PrintStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/**
 * Uma linha JSON por evento, em stderr, sem código, vetores, credenciais, caminhos absolutos nem o texto da consulta.
 * A garantia é do formato: só entram números, booleanos e strings curtas de um alfabeto restrito (ids, modos, status);
 * qualquer outra string vira {@code <redacted>}.
 */
public final class StructuredLog {

    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9_.:-]{0,64}$");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private StructuredLog() {}

    static Object clean(Object value) {
        if (value == null || value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            return value;
        }
        if (value instanceof Double d) {
            return Math.round(d * 1000.0) / 1000.0;
        }
        if (value instanceof String s) {
            return SAFE.matcher(s).matches() ? s : "<redacted>";
        }
        return "<redacted>";
    }

    public static String format(String event, Map<String, Object> fields) {
        Map<String, Object> payload = new TreeMap<>();
        payload.put("event", event);
        payload.put("ts", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        fields.forEach((k, v) -> payload.put(k, clean(v)));
        return JSON.writeValueAsString(payload);
    }

    public static void event(PrintStream out, String event, Map<String, Object> fields) {
        out.println(format(event, fields));
    }
}
