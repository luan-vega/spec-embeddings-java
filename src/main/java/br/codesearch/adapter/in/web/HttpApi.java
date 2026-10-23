package br.codesearch.adapter.in.web;

import br.codesearch.adapter.in.Payloads;
import br.codesearch.adapter.in.StructuredLog;
import br.codesearch.application.AskResponse;
import br.codesearch.application.AskService;
import br.codesearch.application.BoundedRunner;
import br.codesearch.application.SearchResponse;
import br.codesearch.application.SearchService;
import br.codesearch.application.StatusService;
import br.codesearch.domain.CodeSearchException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** HTTP local. /health nunca passa pelo executor de busca, então responde mesmo sob carga. */
@RestController
class HttpApi {

    static final int MAX_BODY_BYTES = 64 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ObjectProvider<SearchService> search;
    private final ObjectProvider<AskService> ask;
    private final StatusService status;
    private final BoundedRunner runner;

    HttpApi(ObjectProvider<SearchService> search, ObjectProvider<AskService> ask, StatusService status, BoundedRunner runner) {
        this.search = search;
        this.ask = ask;
        this.status = status;
        this.runner = runner;
    }

    /** Corpo JSON de até 64 KB, só com Content-Type application/json (exige preflight de CORS). */
    private static JsonNode readJson(HttpServletRequest request) throws IOException {
        String type = request.getContentType() == null ? "" : request.getContentType().split(";")[0].strip().toLowerCase();
        if (!type.equals("application/json")) {
            throw new CodeSearchException("invalid_request", "Content-Type must be application/json");
        }
        byte[] raw = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (raw.length > MAX_BODY_BYTES) {
            throw new CodeSearchException("payload_too_large", "request body is too large");
        }
        try {
            return JSON.readTree(raw);
        } catch (JacksonException e) {
            throw new CodeSearchException("invalid_request", "body is not valid JSON");
        }
    }

    private static ResponseEntity<byte[]> json(int status, Object payload, Map<String, String> headers) {
        ResponseEntity.BodyBuilder b = ResponseEntity.status(status)
                .contentType(new MediaType("application", "json", java.nio.charset.StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore());
        headers.forEach(b::header);
        return b.body(JSON.writeValueAsBytes(payload));
    }

    static ResponseEntity<byte[]> error(CodeSearchException e, String requestId) {
        Map<String, String> headers = e instanceof BoundedRunner.Overloaded o
                ? Map.of(HttpHeaders.RETRY_AFTER, String.valueOf(o.retryAfterSeconds())) : Map.of();
        return json(e.httpStatus(), Payloads.error(e, requestId), headers);
    }

    @GetMapping("/health")
    ResponseEntity<byte[]> health() {
        return json(200, Map.of("status", "ok"), Map.of());
    }

    @GetMapping("/ready")
    ResponseEntity<byte[]> ready() {
        StatusService.Ready ready = status.ready();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ready", ready.ok());
        body.put("checks", ready.checks());
        return json(ready.ok() ? 200 : 503, body, Map.of());
    }

    @PostMapping("/v1/search")
    ResponseEntity<byte[]> search(HttpServletRequest request) {
        String requestId = UUID.randomUUID().toString().replace("-", "");
        long started = System.nanoTime();
        Map<String, Object> log = new LinkedHashMap<>();
        try {
            SearchService.Query query = Payloads.parseSearch(readJson(request), requestId);
            log.put("mode", query.mode());
            SearchService service = CodeSearchException.resolve(search::getIfAvailable);
            if (service == null) {
                throw new CodeSearchException("embedder_unavailable", "search is not available");
            }
            SearchResponse response = runner.run(() -> service.search(query));
            log.put("status", response.status());
            log.put("results", response.results().size());
            log.put("profile_id", response.index().profileId());
            log.put("generation", response.index().generation());
            return json(200, Payloads.searchResponse(response), Map.of());
        } catch (CodeSearchException e) {
            log.put("status", "error");
            log.put("code", e.code());
            return error(e, requestId);
        } catch (IOException | RuntimeException e) { // nunca vaza detalhes internos
            log.put("status", "error");
            log.put("code", "internal_error");
            return error(new CodeSearchException("internal_error", "internal error"), requestId);
        } finally {
            log.put("request_id", requestId);
            log.put("duration_ms", (System.nanoTime() - started) / 1e6);
            StructuredLog.event(System.err, "search", log);
        }
    }

    /** Pergunta em linguagem natural. Fora do executor de busca: as chamadas ao modelo têm o próprio limite de tempo. */
    @PostMapping("/v1/ask")
    ResponseEntity<byte[]> ask(HttpServletRequest request) {
        String requestId = UUID.randomUUID().toString().replace("-", "");
        long started = System.nanoTime();
        Map<String, Object> log = new LinkedHashMap<>();
        try {
            AskService.Ask question = Payloads.parseAsk(readJson(request), requestId);
            AskService service = CodeSearchException.resolve(ask::getIfAvailable);
            if (service == null) {
                throw new CodeSearchException("llm_unavailable", "ask is not available");
            }
            AskResponse response = service.ask(question);
            log.put("status", response.status());
            log.put("sources", response.sources().size());
            log.put("warnings", response.warnings().size());
            return json(200, Payloads.askResponse(response), Map.of());
        } catch (CodeSearchException e) {
            log.put("status", "error");
            log.put("code", e.code());
            return error(e, requestId);
        } catch (IOException | RuntimeException e) {
            log.put("status", "error");
            log.put("code", "internal_error");
            return error(new CodeSearchException("internal_error", "internal error"), requestId);
        } finally {
            log.put("request_id", requestId);
            log.put("duration_ms", (System.nanoTime() - started) / 1e6);
            StructuredLog.event(System.err, "ask", log);
        }
    }
}
