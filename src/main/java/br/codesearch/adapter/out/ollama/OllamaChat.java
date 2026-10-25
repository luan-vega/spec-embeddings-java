package br.codesearch.adapter.out.ollama;

import br.codesearch.application.port.LanguageModel;
import br.codesearch.domain.CodeSearchException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Modelo de linguagem local pelo Ollama em loopback (/api/chat, sem raciocínio). */
public final class OllamaChat implements LanguageModel {

    private final OllamaClient client;
    private final String model;

    public OllamaChat(String model, OllamaClient client) {
        if (model == null || model.isBlank()) {
            throw new CodeSearchException("llm_unavailable", "CODE_SEARCH_LLM_MODEL is required for the ollama LLM");
        }
        this.client = client;
        this.model = model;
    }

    @Override
    public String modelId() {
        return "ollama:" + model;
    }

    private Map<String, Object> body(String system, String user, int maxTokens, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", user)));
        body.put("stream", stream);
        body.put("think", false);
        body.put("options", Map.of("num_predict", maxTokens));
        return body;
    }

    @Override
    public String stream(String system, String user, int maxTokens, Consumer<String> onDelta) {
        StringBuilder all = new StringBuilder();
        int status;
        try {
            status = client.streamLines("/api/chat", body(system, user, maxTokens, true), line -> {
                if (line.has("error")) {
                    throw new CodeSearchException("llm_unavailable", "Ollama failed to answer");
                }
                String delta = line.path("message").path("content").asString("");
                if (!delta.isEmpty()) {
                    all.append(delta);
                    onDelta.accept(delta);
                }
            });
        } catch (CodeSearchException e) {
            throw new CodeSearchException("llm_unavailable", e.getMessage());
        }
        if (status != 200) {
            throw new CodeSearchException("llm_unavailable", "Ollama failed to answer");
        }
        String text = all.toString().strip();
        if (text.isEmpty()) {
            throw new CodeSearchException("llm_unavailable", "the model returned an empty answer");
        }
        return text;
    }

    @Override
    public String complete(String system, String user, int maxTokens) {
        Map<String, Object> body = body(system, user, maxTokens, false);
        OllamaClient.Response resp;
        try {
            resp = client.request("POST", "/api/chat", body);
        } catch (CodeSearchException e) {
            throw new CodeSearchException("llm_unavailable", e.getMessage());
        }
        if (resp.status() != 200 || resp.body() == null || !resp.body().path("message").path("content").isString()) {
            throw new CodeSearchException("llm_unavailable", "Ollama failed to answer");
        }
        String text = resp.body().get("message").get("content").asString().strip();
        if (text.isEmpty()) {
            throw new CodeSearchException("llm_unavailable", "the model returned an empty answer");
        }
        return text;
    }
}
