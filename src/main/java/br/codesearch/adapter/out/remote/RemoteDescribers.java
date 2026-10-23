package br.codesearch.adapter.out.remote;

import br.codesearch.adapter.out.describer.DescriberPrompt;
import br.codesearch.application.port.ChunkDescriber;
import br.codesearch.domain.CanonicalJson;
import br.codesearch.domain.CodeSearchException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * EXTERNOS: descritores que mandam o código do método para a OpenAI, a Claude (Anthropic Messages) ou o Gemini. Só entram com
 * permissão explícita (ver a raiz de composição). A Anthropic não tem API de embeddings, então a Claude só descreve.
 */
public final class RemoteDescribers {

    private RemoteDescribers() {}

    private abstract static class Base implements ChunkDescriber {
        final RemoteHttp http;
        final String model;
        private final String id;

        Base(String provider, RemoteHttp http, String model) {
            if (model == null || model.isBlank()) {
                throw new CodeSearchException("describer_unavailable", "CODE_SEARCH_DESCRIBER_MODEL is required");
            }
            this.http = http;
            this.model = model;
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("provider", provider);
            fields.put("model", model);
            fields.put("prompt", DescriberPrompt.VERSION);
            this.id = CanonicalJson.shortDigest(fields);
        }

        abstract String send(String prompt);

        @Override
        public String describerId() {
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
        public String describe(String symbol, String code) {
            String text = DescriberPrompt.clean(send(DescriberPrompt.build(symbol, code)));
            if (text.isEmpty()) {
                throw new CodeSearchException("describer_unavailable", "the describer model returned an empty description");
            }
            return text;
        }
    }

    public static final class OpenAi extends Base {
        public OpenAi(String baseUrl, String apiKey, String model) {
            super("openai", new RemoteHttp(baseUrl, Map.of("Authorization", "Bearer " + requireKey(apiKey, "OPENAI_API_KEY")),
                    Duration.ofSeconds(120), "describer_unavailable"), model);
        }

        @Override
        String send(String prompt) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
            body.put("max_completion_tokens", DescriberPrompt.MAX_OUTPUT_TOKENS);
            RemoteHttp.Response resp = http.post("chat/completions", body);
            if (resp.status() != 200 || resp.body() == null) {
                throw http.failure(resp, "OpenAI failed to generate a description");
            }
            JsonNode content = resp.body().path("choices").path(0).path("message").path("content");
            if (!content.isString()) {
                throw new CodeSearchException("describer_unavailable", "unexpected response from OpenAI");
            }
            return content.asString();
        }
    }

    public static final class Claude extends Base {
        public Claude(String baseUrl, String apiKey, String model) {
            super("anthropic", new RemoteHttp(baseUrl,
                    Map.of("x-api-key", requireKey(apiKey, "ANTHROPIC_API_KEY"), "anthropic-version", "2023-06-01"),
                    Duration.ofSeconds(120), "describer_unavailable"), model);
        }

        @Override
        String send(String prompt) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("max_tokens", DescriberPrompt.MAX_OUTPUT_TOKENS); // sem temperature: a API recusa amostragem (400)
            body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
            RemoteHttp.Response resp = http.post("v1/messages", body);
            if (resp.status() != 200 || resp.body() == null) {
                throw http.failure(resp, "Claude failed to generate a description");
            }
            StringBuilder text = new StringBuilder();
            for (JsonNode block : resp.body().path("content")) {
                if ("text".equals(block.path("type").asString(""))) {
                    text.append(block.path("text").asString("")).append(' ');
                }
            }
            if (text.isEmpty()) {
                throw new CodeSearchException("describer_unavailable", "unexpected response from Claude");
            }
            return text.toString();
        }
    }

    public static final class Gemini extends Base {
        public Gemini(String baseUrl, String apiKey, String model) {
            super("gemini", new RemoteHttp(baseUrl, Map.of("x-goog-api-key", requireKey(apiKey, "GEMINI_API_KEY")),
                    Duration.ofSeconds(120), "describer_unavailable"), model);
        }

        @Override
        String send(String prompt) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))));
            body.put("generationConfig", Map.of("temperature", 0, "maxOutputTokens", DescriberPrompt.MAX_OUTPUT_TOKENS));
            RemoteHttp.Response resp = http.post("models/" + model + ":generateContent", body);
            if (resp.status() != 200 || resp.body() == null) {
                throw http.failure(resp, "Gemini failed to generate a description");
            }
            StringBuilder text = new StringBuilder();
            for (JsonNode part : resp.body().path("candidates").path(0).path("content").path("parts")) {
                if (!part.path("thought").asBoolean(false)) {
                    text.append(part.path("text").asString("")).append(' ');
                }
            }
            if (text.isEmpty()) {
                throw new CodeSearchException("describer_unavailable", "unexpected response from Gemini");
            }
            return text.toString();
        }
    }

    private static String requireKey(String key, String name) {
        if (key == null || key.isBlank()) {
            throw new CodeSearchException("describer_unavailable", name + " is required for this describer");
        }
        return key;
    }
}
