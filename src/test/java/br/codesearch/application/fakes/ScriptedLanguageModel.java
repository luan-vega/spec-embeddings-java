package br.codesearch.application.fakes;

import br.codesearch.application.port.LanguageModel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/** Modelo de teste: devolve as respostas na ordem dada e guarda o que recebeu. */
public class ScriptedLanguageModel implements LanguageModel {

    public record Call(String system, String user, int maxTokens) {}

    private final String id;
    private final boolean local;
    private final Deque<String> replies = new ArrayDeque<>();
    public final List<Call> calls = new ArrayList<>();

    public ScriptedLanguageModel(String id, boolean local, String... replies) {
        this.id = id;
        this.local = local;
        this.replies.addAll(List.of(replies));
    }

    public ScriptedLanguageModel reply(String text) {
        replies.add(text);
        return this;
    }

    @Override
    public String modelId() {
        return id;
    }

    @Override
    public String complete(String system, String user, int maxTokens) {
        calls.add(new Call(system, user, maxTokens));
        if (replies.isEmpty()) {
            throw new IllegalStateException("no scripted reply left");
        }
        return replies.poll();
    }

    /** Entrega a resposta em pedaços de 3 caracteres, como um streaming real que corta palavras ao meio. */
    @Override
    public String stream(String system, String user, int maxTokens, Consumer<String> onDelta) {
        String text = complete(system, user, maxTokens);
        for (int i = 0; i < text.length(); i += 3) {
            onDelta.accept(text.substring(i, Math.min(text.length(), i + 3)));
        }
        return text;
    }

    @Override
    public boolean local() {
        return local;
    }
}
