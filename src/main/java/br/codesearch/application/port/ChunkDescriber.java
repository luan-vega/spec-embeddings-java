package br.codesearch.application.port;

/** Resume um método em uma frase em inglês (opcional). {@code describerId} identifica modelo e prompt. */
public interface ChunkDescriber {

    String describerId();

    /** Uma linha de texto. Falha com describer_unavailable; nunca devolve texto vazio. */
    String describe(String symbol, String code);
}
