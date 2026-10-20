package br.codesearch.application.port;

import br.codesearch.domain.ExclusionPolicy;

/** Onde mora a política de exclusão. Ausente ou inválida significa "indexa nada". */
public interface PolicyStore {

    ExclusionPolicy load();

    boolean exists();

    void writeDefault();
}
