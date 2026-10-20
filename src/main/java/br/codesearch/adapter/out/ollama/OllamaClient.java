package br.codesearch.adapter.out.ollama;

import br.codesearch.domain.CodeSearchException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** HTTP mínimo para o Ollama. Recusa qualquer host que não seja loopback, para nada sair da máquina. */
public final class OllamaClient {

    public static final String DEFAULT_URL = "http://127.0.0.1:11434";
    private static final Pattern IP_LITERAL = Pattern.compile("^(\\d{1,3}(\\.\\d{1,3}){3}|\\[?[0-9A-Fa-f:]+\\]?)$");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record Response(int status, JsonNode body) {}

    private final URI base;
    private final Duration timeout;
    private final HttpClient http;

    public OllamaClient(String url, Duration timeout) {
        URI uri = URI.create(url);
        if (!"http".equals(uri.getScheme()) || !isLoopback(uri.getHost())) {
            throw new CodeSearchException("embedder_unavailable", "the Ollama URL must be http on a loopback address");
        }
        this.base = uri;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    static boolean isLoopback(String host) {
        if (host == null) {
            return false;
        }
        if (host.equals("localhost")) {
            return true;
        }
        if (!IP_LITERAL.matcher(host).matches()) {
            return false; // nome de domínio: nunca resolve DNS para decidir
        }
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (IOException e) {
            return false;
        }
    }

    public Response request(String method, String path, Map<String, Object> body) {
        HttpRequest.Builder req = HttpRequest.newBuilder(base.resolve(path)).timeout(timeout);
        if (body != null) {
            req.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)));
        } else {
            req.method(method, HttpRequest.BodyPublishers.noBody());
        }
        try {
            HttpResponse<byte[]> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
            byte[] raw = resp.body();
            return new Response(resp.statusCode(), raw.length == 0 ? null : JSON.readTree(raw));
        } catch (IOException e) {
            throw new CodeSearchException("embedder_unavailable", "Ollama is not reachable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CodeSearchException("embedder_unavailable", "interrupted");
        } catch (JacksonException e) {
            throw new CodeSearchException("embedder_invalid_output", "Ollama returned invalid JSON");
        }
    }
}
