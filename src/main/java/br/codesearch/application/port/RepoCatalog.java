package br.codesearch.application.port;

import br.codesearch.domain.RepoEntry;
import java.nio.file.Path;
import java.util.List;

/** Registro de repositórios com administração. Remover um repo altera só o registro, não os snapshots. */
public interface RepoCatalog extends RepoRegistry {

    RepoEntry add(String repoId, Path path, List<String> roots);

    boolean remove(String repoId);

    void setEnabled(String repoId, boolean enabled);
}
