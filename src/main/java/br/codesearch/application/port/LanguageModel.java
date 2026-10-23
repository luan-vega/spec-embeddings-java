package br.codesearch.application.port;

/** Modelo de linguagem usado pela pergunta ({@code ask}): valida e reescreve a consulta e redige a resposta. */
public interface LanguageModel {

    /** Provedor e modelo, por exemplo {@code claude:claude-haiku-5-5}. */
    String modelId();

    /** Uma resposta em texto. Falha com llm_unavailable; nunca devolve texto vazio. */
    String complete(String system, String user, int maxTokens);

    /** Falso quando o texto enviado sai da máquina (provedor externo). */
    default boolean local() {
        return true;
    }
}
