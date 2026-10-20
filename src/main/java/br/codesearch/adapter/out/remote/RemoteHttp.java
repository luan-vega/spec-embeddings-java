package br.codesearch.adapter.out.remote;

import br.codesearch.domain.CodeSearchException;
import br.codesearch.domain.Loopback;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * HTTP para provedores EXTERNOS (OpenAI, Claude). Só HTTPS, exceto loopback (servidor compatível rodando na própria máquina).
 * A chave vai só no cabeçalho e nunca aparece em mensagens de erro nem em logs.
 */
final class RemoteHttp {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    record Response(int status, JsonNode body) {}

    private final URI base;
    private final Map<String, String> headers;
    private final Duration timeout;
    private final HttpClient http;
    private final String errorCode;

    /** {@code errorCode}: embedder_unavailable ou describer_unavailable, conforme quem usa. */
    RemoteHttp(String baseUrl, Map<String, String> headers, Duration timeout, String errorCode) {
        URI uri = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
        boolean secure = "https".equals(uri.getScheme());
        if (!secure && !("http".equals(uri.getScheme()) && Loopback.isLoopback(uri.getHost()))) {
            throw new CodeSearchException(errorCode, "the provider URL must be https (http only on loopback)");
        }
        this.base = uri;
        this.headers = headers;
        this.timeout = timeout;
        this.errorCode = errorCode;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    String host() {
        return base.getHost();
    }

    Response post(String path, Map<String, Object> body) {
        HttpRequest.Builder req = HttpRequest.newBuilder(base.resolve(path)).timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)));
        headers.forEach(req::header);
        try {
            HttpResponse<byte[]> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Response(resp.statusCode(), resp.body().length == 0 ? null : JSON.readTree(resp.body()));
        } catch (IOException e) {
            throw new CodeSearchException(errorCode, "the provider is not reachable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CodeSearchException(errorCode, "interrupted");
        } catch (JacksonException e) {
            throw new CodeSearchException(errorCode.equals("embedder_unavailable") ? "embedder_invalid_output" : errorCode,
                    "the provider returned invalid JSON");
        }
    }

    /** Erro estável por status, sem repassar o corpo do provedor (pode ecoar o código enviado). */
    CodeSearchException failure(Response resp, String what) {
        String reason = switch (resp.status()) {
            case 401, 403 -> "authentication failed (check the API key)";
            case 429 -> "rate limited, retry later";
            case 400, 413, 422 -> "input rejected by the provider";
            default -> "HTTP " + resp.status();
        };
        boolean rejected = resp.status() == 400 || resp.status() == 413 || resp.status() == 422;
        return new CodeSearchException(rejected && errorCode.equals("embedder_unavailable") ? "embedder_invalid_output" : errorCode,
                what + ": " + reason);
    }
}
