package br.codesearch.application.port;

import br.codesearch.domain.EmbeddingProfile;
import java.util.List;

/** Gera vetores. O perfil (modelo, dimensão, prefixos) pertence ao adapter, não ao núcleo. */
public interface Embedder {

    EmbeddingProfile profile();

    /** Um vetor float[dim], normalizado, por texto. Falha com embedder_unavailable; nunca devolve zeros. */
    List<float[]> embedDocuments(List<String> texts);

    float[] embedQuery(String text);
}
