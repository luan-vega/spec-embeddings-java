package br.codesearch.adapter.in.mcp;

import br.codesearch.adapter.in.Payloads;
import br.codesearch.application.AskService;
import br.codesearch.application.BoundedRunner;
import br.codesearch.application.SearchResponse;
import br.codesearch.application.SearchService;
import br.codesearch.application.StatusService;
import br.codesearch.domain.CodeSearchException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** MCP por stdio (JSON-RPC 2.0, uma mensagem por linha). Somente leitura: nenhuma ferramenta escreve no índice. */
public final class McpServer {

    static final List<String> SUPPORTED_PROTOCOLS = List.of("2025-06-18", "2025-03-26", "2024-11-05");
    static final int MAX_TEXT_CHARS = 4000; // por resultado devolvido ao cliente MCP
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Supplier<SearchService> search;
    private final Supplier<AskService> ask;
    private final StatusService status;
    private final BoundedRunner runner;

    /** {@code search} pode falhar com embedder_unavailable; o erro volta como resultado da ferramenta. */
    public McpServer(Supplier<SearchService> search, StatusService status, BoundedRunner runner) {
        this(search, null, status, runner);
    }

    /** {@code ask} nulo: sem a ferramenta ask_code. */
    public McpServer(Supplier<SearchService> search, Supplier<AskService> ask, StatusService status, BoundedRunner runner) {
        this.search = search;
        this.ask = ask;
        this.status = status;
        this.runner = runner;
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        schema.put("additionalProperties", false);
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("name", name);
        t.put("description", description);
        t.put("inputSchema", schema);
        t.put("annotations", Map.of("readOnlyHint", true));
        return t;
    }

    private List<Map<String, Object>> tools() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("query", Map.of("type", "string"));
        props.put("mode", Map.of("type", "string", "enum", List.of("hybrid", "semantic", "lexical")));
        props.put("repos", Map.of("type", "array", "items", Map.of("type", "string")));
        props.put("languages", Map.of("type", "array", "items", Map.of("type", "string")));
        props.put("path_prefix", Map.of("type", "string"));
        props.put("top_k", Map.of("type", "integer", "minimum", 1));
        List<Map<String, Object>> out = new java.util.ArrayList<>(List.of(
                tool("search_code", "Busca código Java nos repositórios indexados (híbrida, semântica ou lexical). Somente leitura.", props, List.of("query")),
                tool("code_search_status", "Estado do índice ativo e dos repositórios (revisão, data de indexação). Somente leitura.", Map.of(), List.of())));
        if (ask != null) {
            Map<String, Object> askProps = new LinkedHashMap<>();
            askProps.put("question", Map.of("type", "string"));
            askProps.put("repos", Map.of("type", "array", "items", Map.of("type", "string")));
            askProps.put("languages", Map.of("type", "array", "items", Map.of("type", "string")));
            askProps.put("path_prefix", Map.of("type", "string"));
            askProps.put("top_k", Map.of("type", "integer", "minimum", 1, "maximum", AskService.MAX_TOP_K));
            out.add(tool("ask_code", "Pergunta sobre o código em linguagem natural: valida a pergunta (ou pede esclarecimento), "
                    + "busca e responde citando os trechos [n]. Usa um modelo de linguagem, que pode ser externo. Somente leitura.",
                    askProps, List.of("question")));
        }
        return out;
    }

    private static Map<String, Object> result(JsonNode id, Object result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("result", result);
        return m;
    }

    private static Map<String, Object> rpcError(JsonNode id, int code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("error", Map.of("code", code, "message", message));
        return m;
    }

    private static Map<String, Object> toolText(Object payload, boolean isError) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("content", List.of(Map.of("type", "text", "text", JSON.writeValueAsString(payload))));
        m.put("isError", isError);
        return m;
    }

    /** Responde uma mensagem; null para notificações. */
    public Map<String, Object> handle(JsonNode message) {
        if (message == null || !message.isObject() || !"2.0".equals(message.path("jsonrpc").asString(null))
                || !message.path("method").isString()) {
            return rpcError(message != null && message.isObject() ? message.get("id") : null, -32600, "invalid request");
        }
        String method = message.get("method").asString();
        JsonNode id = message.get("id");
        boolean notification = !message.has("id");
        JsonNode params = message.has("params") && !message.get("params").isNull() ? message.get("params") : JSON.createObjectNode();
        switch (method) {
            case "initialize" -> {
                String wanted = params.path("protocolVersion").asString(null);
                String version = wanted != null && SUPPORTED_PROTOCOLS.contains(wanted) ? wanted : SUPPORTED_PROTOCOLS.get(0);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("protocolVersion", version);
                r.put("capabilities", Map.of("tools", Map.of("listChanged", false)));
                r.put("serverInfo", Map.of("name", "code-search", "version", "0.1.0"));
                return result(id, r);
            }
            case "ping" -> {
                return result(id, Map.of());
            }
            case "tools/list" -> {
                return result(id, Map.of("tools", tools()));
            }
            case "tools/call" -> {
                return params.isObject() ? result(id, call(params)) : rpcError(id, -32602, "invalid params");
            }
            default -> {
                return notification ? null : rpcError(id, -32601, "method not found");
            }
        }
    }

    private Map<String, Object> call(JsonNode params) {
        String name = params.path("name").asString("");
        JsonNode args = params.has("arguments") && !params.get("arguments").isNull() ? params.get("arguments") : JSON.createObjectNode();
        try {
            if (name.equals("search_code")) {
                SearchService.Query query = Payloads.parseSearch(args, null);
                SearchService service = search.get();
                SearchResponse response = runner.run(() -> service.search(query));
                Map<String, Object> payload = Payloads.searchResponse(response);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> results = (List<Map<String, Object>>) payload.get("results");
                for (Map<String, Object> item : results) {
                    String text = (String) item.get("text");
                    item.put("text", text.length() > MAX_TEXT_CHARS ? text.substring(0, MAX_TEXT_CHARS) : text);
                }
                return toolText(payload, false);
            }
            if (name.equals("ask_code") && ask != null) {
                AskService.Ask question = Payloads.parseAsk(args, null);
                return toolText(Payloads.askResponse(ask.get().ask(question)), false);
            }
            if (name.equals("code_search_status")) {
                return toolText(status.status(), false);
            }
            return toolText(Map.of("error", Map.of("code", "invalid_request", "message", "unknown tool")), true);
        } catch (CodeSearchException e) {
            return toolText(Map.of("error", Map.of("code", e.code(), "message", e.getMessage())), true);
        }
    }

    public void serve(InputStream in, OutputStream out) throws IOException {
        PrintStream writer = new PrintStream(out, true, StandardCharsets.UTF_8);
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.strip();
            if (line.isEmpty()) {
                continue;
            }
            Map<String, Object> reply;
            try {
                reply = handle(JSON.readTree(line));
            } catch (JacksonException e) {
                reply = rpcError(null, -32700, "parse error");
            }
            if (reply != null) {
                writer.print(JSON.writeValueAsString(reply) + "\n");
                writer.flush();
            }
        }
    }
}
