package br.codesearch.adapter.out.remote;

import br.codesearch.application.port.LanguageModel;
import br.codesearch.domain.CodeSearchException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;

/**
 * EXTERNOS: modelos de linguagem da Claude (Anthropic Messages), da OpenAI e do Gemini para a pergunta ({@code ask}). A
 * pergunta e os trechos de código achados saem da máquina; só entram com permissão explícita (ver a raiz de composição).
 */
public final class RemoteChats {

    private static final String UNAVAILABLE = "llm_unavailable";

    private RemoteChats() {}

    private abstract static class Base implements LanguageModel {
        final RemoteHttp http;
        final String model;
        private final String id;

        Base(String provider, RemoteHttp http, String model) {
            if (model == null || model.isBlank()) {
                throw new CodeSearchException(UNAVAILABLE, "a model is required for the " + provider + " LLM");
            }
            this.http = http;
            this.model = model;
            this.id = provider + ":" + model;
        }

        abstract String send(String system, String user, int maxTokens);

        /** Mesma chamada de {@link #send} em streaming (SSE); entrega cada pedaço de texto a {@code onDelta}. */
        abstract void sendStream(String system, String user, int maxTokens, Consumer<String> onDelta);

        @Override
        public String modelId() {
            return id;
        }

        @Override
        public boolean local() {
            return false;
        }

        public String host() {
            return http.host();
        }

        @Override
        public String complete(String system, String user, int maxTokens) {
            String text = send(system, user, maxTokens).strip();
            if (text.isEmpty()) {
                throw new CodeSearchException(UNAVAILABLE, "the model returned an empty answer");
            }
            return text;
        }

        @Override
        public String stream(String system, String user, int maxTokens, Consumer<String> onDelta) {
            StringBuilder all = new StringBuilder();
            sendStream(system, user, maxTokens, d -> {
                if (!d.isEmpty()) {
                    all.append(d);
                    onDelta.accept(d);
                }
            });
            String text = all.toString().strip();
            if (text.isEmpty()) {
                throw new CodeSearchException(UNAVAILABLE, "the model returned an empty answer");
            }
            return text;
        }

        void stream(String path, Map<String, Object> body, String what, Consumer<JsonNode> onData) {
            int status = http.postSse(path, body, onData);
            if (status != 200) {
                throw http.failure(new RemoteHttp.Response(status, null), what);
            }
        }
    }

    /**
     * Claude pela API Messages. {@code effort} (low, medium, high...) controla quanto o modelo pensa; vazio usa o padrão do
     * modelo. Sem temperature: a API recusa parâmetros de amostragem.
     */
    public static final class Claude extends Base {
        private final String effort;

        public Claude(String baseUrl, String apiKey, String model, String effort) {
            super("claude", new RemoteHttp(baseUrl,
                    Map.of("x-api-key", requireKey(apiKey, "ANTHROPIC_API_KEY"), "anthropic-version", "2023-06-01"),
                    Duration.ofSeconds(120), UNAVAILABLE), model);
            this.effort = effort == null ? "" : effort.strip();
        }

        private Map<String, Object> body(String system, String user, int maxTokens) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("max_tokens", maxTokens);
            body.put("system", system);
            body.put("messages", List.of(Map.of("role", "user", "content", user)));
            if (!effort.isEmpty()) {
                body.put("output_config", Map.of("effort", effort));
            }
            return body;
        }

        /** Eventos content_block_delta trazem o texto; message_delta traz o stop_reason; error encerra. */
        @Override
        void sendStream(String system, String user, int maxTokens, Consumer<String> onDelta) {
            Map<String, Object> body = body(system, user, maxTokens);
            body.put("stream", true);
            stream("v1/messages", body, "Claude failed to answer", event -> {
                switch (event.path("type").asString("")) {
                    case "content_block_delta" -> {
                        if ("text_delta".equals(event.path("delta").path("type").asString(""))) {
                            onDelta.accept(event.path("delta").path("text").asString(""));
                        }
                    }
                    case "message_delta" -> {
                        if ("refusal".equals(event.path("delta").path("stop_reason").asString(""))) {
                            throw new CodeSearchException(UNAVAILABLE, "Claude declined to answer this request");
                        }
                    }
                    case "error" -> throw new CodeSearchException(UNAVAILABLE, "Claude failed to answer: stream interrupted");
                    default -> {
                    }
                }
            });
        }

        @Override
        String send(String system, String user, int maxTokens) {
            RemoteHttp.Response resp = http.post("v1/messages", body(system, user, maxTokens));
            if (resp.status() != 200 || resp.body() == null) {
                throw http.failure(resp, "Claude failed to answer");
            }
            if ("refusal".equals(resp.body().path("stop_reason").asString(""))) {
                throw new CodeSearchException(UNAVAILABLE, "Claude declined to answer this request");
            }
            StringBuilder text = new StringBuilder();
            for (JsonNode block : resp.body().path("content")) {
                if ("text".equals(block.path("type").asString(""))) {
                    text.append(block.path("text").asString(""));
                }
            }
            return text.toString();
        }
    }

    public static final class OpenAi extends Base {
        public OpenAi(String baseUrl, String apiKey, String model) {
            super("openai", new RemoteHttp(baseUrl, Map.of("Authorization", "Bearer " + requireKey(apiKey, "OPENAI_API_KEY")),
                    Duration.ofSeconds(120), UNAVAILABLE), model);
        }

        private Map<String, Object> body(String system, String user, int maxTokens) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", user)));
            body.put("max_completion_tokens", maxTokens);
            return body;
        }

        @Override
        void sendStream(String system, String user, int maxTokens, Consumer<String> onDelta) {
            Map<String, Object> body = body(system, user, maxTokens);
            body.put("stream", true);
            stream("chat/completions", body, "OpenAI failed to answer", chunk -> {
                if (chunk.has("error")) {
                    throw new CodeSearchException(UNAVAILABLE, "OpenAI failed to answer: stream interrupted");
                }
                JsonNode content = chunk.path("choices").path(0).path("delta").path("content");
                if (content.isString()) {
                    onDelta.accept(content.asString());
                }
            });
        }

        @Override
        String send(String system, String user, int maxTokens) {
            RemoteHttp.Response resp = http.post("chat/completions", body(system, user, maxTokens));
            if (resp.status() != 200 || resp.body() == null) {
                throw http.failure(resp, "OpenAI failed to answer");
            }
            JsonNode content = resp.body().path("choices").path(0).path("message").path("content");
            if (!content.isString()) {
                throw new CodeSearchException(UNAVAILABLE, "unexpected response from OpenAI");
            }
            return content.asString();
        }
    }

    public static final class Gemini extends Base {
        public Gemini(String baseUrl, String apiKey, String model) {
            super("gemini", new RemoteHttp(baseUrl, Map.of("x-goog-api-key", requireKey(apiKey, "GEMINI_API_KEY")),
                    Duration.ofSeconds(120), UNAVAILABLE), model);
        }

        private static Map<String, Object> body(String system, String user, int maxTokens) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", system))));
            body.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", user)))));
            body.put("generationConfig", Map.of("maxOutputTokens", maxTokens));
            return body;
        }

        /** Texto das partes de uma resposta (inteira ou um pedaço do streaming), sem as partes de raciocínio. */
        private static String text(JsonNode response) {
            StringBuilder text = new StringBuilder();
            for (JsonNode part : response.path("candidates").path(0).path("content").path("parts")) {
                if (!part.path("thought").asBoolean(false)) {
                    text.append(part.path("text").asString(""));
                }
            }
            return text.toString();
        }

        @Override
        void sendStream(String system, String user, int maxTokens, Consumer<String> onDelta) {
            stream("models/" + model + ":streamGenerateContent?alt=sse", body(system, user, maxTokens),
                    "Gemini failed to answer", chunk -> onDelta.accept(text(chunk)));
        }

        @Override
        String send(String system, String user, int maxTokens) {
            RemoteHttp.Response resp = http.post("models/" + model + ":generateContent", body(system, user, maxTokens));
            if (resp.status() != 200 || resp.body() == null) {
                throw http.failure(resp, "Gemini failed to answer");
            }
            return text(resp.body());
        }
    }

    private static String requireKey(String key, String name) {
        if (key == null || key.isBlank()) {
            throw new CodeSearchException(UNAVAILABLE, name + " is required for this LLM");
        }
        return key;
    }
}
