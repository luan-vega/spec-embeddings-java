package br.codesearch.application.port;

import java.util.function.Consumer;

/** Modelo de linguagem usado pela pergunta ({@code ask}): valida e reescreve a consulta e redige a resposta. */
public interface LanguageModel {

    /** Provedor e modelo, por exemplo {@code claude:claude-haiku-5-5}. */
    String modelId();

    /** Uma resposta em texto. Falha com llm_unavailable; nunca devolve texto vazio. */
    String complete(String system, String user, int maxTokens);

    /**
     * Como {@link #complete}, mas entrega o texto aos pedaços em {@code onDelta} conforme o modelo gera, e devolve o texto
     * completo no fim. Provedor sem streaming entrega tudo de uma vez.
     */
    default String stream(String system, String user, int maxTokens, Consumer<String> onDelta) {
        String text = complete(system, user, maxTokens);
        onDelta.accept(text);
        return text;
    }

    /** Falso quando o texto enviado sai da máquina (provedor externo). */
    default boolean local() {
        return true;
    }
}
