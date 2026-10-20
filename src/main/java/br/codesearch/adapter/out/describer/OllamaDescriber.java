package br.codesearch.adapter.out.describer;

import br.codesearch.adapter.out.ollama.OllamaClient;
import br.codesearch.application.port.ChunkDescriber;
import br.codesearch.domain.CanonicalJson;
import br.codesearch.domain.CodeSearchException;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** Descritor local: uma frase em inglês por método, determinística (temperatura 0), pelo Ollama em loopback. */
public final class OllamaDescriber implements ChunkDescriber {

    private final OllamaClient client;
    private final String model;
    private final String describerId;

    public OllamaDescriber(String model, OllamaClient client) {
        if (model == null || model.isBlank()) {
            throw unavailable("CODE_SEARCH_DESCRIBER_MODEL is required");
        }
        this.client = client;
        this.model = model;
        OllamaClient.Response tags = request("GET", "/api/tags", null);
        if (tags.status() != 200 || tags.body() == null) {
            throw unavailable("Ollama did not list models");
        }
        String digest = null;
        for (JsonNode m : tags.body().path("models")) {
            String name = m.path("name").asString("");
            if (name.equals(model) || (!model.contains(":") && name.equals(model + ":latest"))) {
                digest = m.path("digest").asString("");
                break;
            }
        }
        if (digest == null || digest.isEmpty()) {
            throw unavailable("model '" + model + "' is not installed in Ollama (pull it manually)");
        }
        Map<String, Object> id = new LinkedHashMap<>();
        id.put("model", model);
        id.put("digest", digest);
        id.put("prompt", DescriberPrompt.VERSION);
        this.describerId = CanonicalJson.shortDigest(id);
    }

    private static CodeSearchException unavailable(String message) {
        return new CodeSearchException("describer_unavailable", message);
    }

    private OllamaClient.Response request(String method, String path, Map<String, Object> body) {
        try {
            return client.request(method, path, body);
        } catch (CodeSearchException e) {
            throw unavailable(e.getMessage());
        }
    }

    @Override
    public String describerId() {
        return describerId;
    }

    @Override
    public String describe(String symbol, String code) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("prompt", DescriberPrompt.build(symbol, code));
        body.put("stream", false);
        body.put("think", false);
        body.put("options", Map.of("temperature", 0, "seed", 0, "num_predict", DescriberPrompt.MAX_OUTPUT_TOKENS));
        OllamaClient.Response resp = request("POST", "/api/generate", body);
        if (resp.status() != 200 || resp.body() == null || !resp.body().path("response").isString()) {
            throw unavailable("Ollama failed to generate a description");
        }
        String text = DescriberPrompt.clean(resp.body().get("response").asString());
        if (text.isEmpty()) {
            throw unavailable("the describer model returned an empty description");
        }
        return text;
    }
}
