package br.codesearch.adapter.out.ollama;

import br.codesearch.application.port.LanguageModel;
import br.codesearch.domain.CodeSearchException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Modelo de linguagem local pelo Ollama em loopback (/api/chat, sem streaming e sem raciocínio). */
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

    @Override
    public String complete(String system, String user, int maxTokens) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", user)));
        body.put("stream", false);
        body.put("think", false);
        body.put("options", Map.of("num_predict", maxTokens));
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
