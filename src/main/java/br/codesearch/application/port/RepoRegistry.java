package br.codesearch.application.port;

import br.codesearch.domain.RepoEntry;
import java.util.List;

public interface RepoRegistry {

    List<RepoEntry> list();

    List<RepoEntry> enabled();
}
