package br.codesearch.adapter.in;

import br.codesearch.application.AskResponse;
import br.codesearch.application.AskService;
import br.codesearch.application.SearchService;
import br.codesearch.application.SearchResponse;
import br.codesearch.domain.ChunkView;
import br.codesearch.domain.CodeSearchException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** Formato JSON das respostas e validação estrita do corpo das buscas, compartilhados por HTTP, MCP e CLI. */
public final class Payloads {

    private static final List<String> ALLOWED_FIELDS = List.of("query", "mode", "repos", "languages", "path_prefix", "top_k");
    private static final List<String> ASK_FIELDS = List.of("question", "repos", "languages", "path_prefix", "top_k");

    private Payloads() {}

    private static Map<String, Object> chunk(ChunkView c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("repo", c.repo());
        m.put("source", c.source());
        m.put("symbol", c.symbol());
        m.put("start_line", c.startLine());
        m.put("end_line", c.endLine());
        return m;
    }

    private static Map<String, Object> coverage(SearchResponse.Coverage cov) {
        List<Map<String, Object>> unavailable = new ArrayList<>();
        for (SearchResponse.Unavailable u : cov.unavailable()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("repo", u.repo());
            m.put("reason", u.reason());
            unavailable.add(m);
        }
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("searched", cov.searched());
        coverage.put("unavailable", unavailable);
        return coverage;
    }

    /** Resposta de uma pergunta. Os trechos vão sem o código (o cliente abre pelo caminho e pelas linhas). */
    public static Map<String, Object> askResponse(AskResponse r) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("intent", r.plan().intent());
        plan.put("identifiers", r.plan().identifiers());
        plan.put("search_text", r.plan().searchText());
        plan.put("queries", r.plan().queries());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", r.status());
        out.put("answer", r.answer());
        out.put("questions", r.questions());
        out.put("plan", plan);
        out.put("sources", sources(r.sources()));
        out.put("warnings", r.warnings());
        out.put("coverage", r.coverage() == null ? null : coverage(r.coverage()));
        out.put("models", Map.of("validator", r.models().validator(), "answerer", r.models().answerer()));
        out.put("timings_ms", r.timingsMs());
        out.put("request_id", r.requestId());
        return out;
    }

    /** Trechos de uma pergunta, sem o código. */
    public static List<Map<String, Object>> sources(List<AskResponse.Source> list) {
        List<Map<String, Object>> sources = new ArrayList<>();
        for (AskResponse.Source s : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("n", s.n());
            m.putAll(chunk(s.chunk()));
            m.put("score", s.score());
            m.put("cited", s.cited());
            sources.add(m);
        }
        return sources;
    }

    public static Map<String, Object> searchResponse(SearchResponse r) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (SearchResponse.Result item : r.results()) {
            ChunkView c = item.chunk();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("repo", c.repo());
            m.put("source", c.source());
            m.put("symbol", c.symbol());
            m.put("parent_symbol", c.parentSymbol());
            m.put("start_line", c.startLine());
            m.put("end_line", c.endLine());
            m.put("language", c.language());
            m.put("score", item.score());
            m.put("text", c.text());
            m.put("repo_revision", c.repoRevision());
            m.put("indexed_at", c.indexedAt());
            results.add(m);
        }
        List<Map<String, Object>> unavailable = new ArrayList<>();
        for (SearchResponse.Unavailable u : r.coverage().unavailable()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("repo", u.repo());
            m.put("reason", u.reason());
            unavailable.add(m);
        }
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("searched", r.coverage().searched());
        coverage.put("unavailable", unavailable);
        Map<String, Object> index = new LinkedHashMap<>();
        index.put("profile_id", r.index().profileId());
        index.put("generation", r.index().generation());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", r.status());
        out.put("results", results);
        out.put("coverage", coverage);
        out.put("index", index);
        out.put("request_id", r.requestId());
        return out;
    }

    public static Map<String, Object> error(CodeSearchException e, String requestId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", e.code());
        body.put("message", e.getMessage());
        if (requestId != null) {
            body.put("request_id", requestId);
        }
        return Map.of("error", body);
    }

    private static CodeSearchException invalid(String message) {
        return new CodeSearchException("invalid_request", message);
    }

    private static List<String> stringList(JsonNode value, String name) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isArray()) {
            throw invalid(name + " must be a list of strings");
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isString()) {
                throw invalid(name + " must be a list of strings");
            }
            out.add(item.asString());
        }
        return out;
    }

    /** Valida o corpo de uma pergunta (HTTP ou argumentos da ferramenta MCP). Campo desconhecido é erro. */
    public static AskService.Ask parseAsk(JsonNode body, String requestId) {
        if (body == null || !body.isObject()) {
            throw invalid("body must be a JSON object");
        }
        for (var field : body.properties()) {
            if (!ASK_FIELDS.contains(field.getKey())) {
                throw invalid("unknown field in request");
            }
        }
        JsonNode question = body.get("question");
        if (question == null || !question.isString()) {
            throw invalid("question must be a string");
        }
        JsonNode prefix = body.get("path_prefix");
        if (prefix != null && !prefix.isNull() && !prefix.isString()) {
            throw invalid("path_prefix must be a string");
        }
        JsonNode topK = body.get("top_k");
        if (topK != null && !topK.isIntegralNumber()) {
            throw invalid("top_k out of range");
        }
        return new AskService.Ask(question.asString(), stringList(body.get("repos"), "repos"),
                stringList(body.get("languages"), "languages"), prefix == null || prefix.isNull() ? null : prefix.asString(),
                topK == null ? 8 : topK.asInt(), requestId);
    }

    /** Valida o corpo de uma busca (HTTP ou argumentos da ferramenta MCP). Campo desconhecido é erro. */
    public static SearchService.Query parseSearch(JsonNode body, String requestId) {
        if (body == null || !body.isObject()) {
            throw invalid("body must be a JSON object");
        }
        for (var field : body.properties()) {
            if (!ALLOWED_FIELDS.contains(field.getKey())) {
                throw invalid("unknown field in request");
            }
        }
        JsonNode query = body.get("query");
        if (query == null || !query.isString()) {
            throw invalid("query must be a string");
        }
        JsonNode prefix = body.get("path_prefix");
        if (prefix != null && !prefix.isNull() && !prefix.isString()) {
            throw invalid("path_prefix must be a string");
        }
        JsonNode mode = body.get("mode");
        if (mode != null && !mode.isString()) {
            throw invalid("mode must be hybrid, semantic or lexical");
        }
        JsonNode topK = body.get("top_k");
        if (topK != null && !topK.isIntegralNumber()) {
            throw invalid("top_k out of range");
        }
        return new SearchService.Query(
                query.asString(), mode == null ? "hybrid" : mode.asString(), stringList(body.get("repos"), "repos"),
                stringList(body.get("languages"), "languages"), prefix == null || prefix.isNull() ? null : prefix.asString(),
                topK == null ? 10 : topK.asInt(), requestId);
    }
}
