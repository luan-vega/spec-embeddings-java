package br.codesearch.application.fakes;

import br.codesearch.application.port.RepoRegistry;
import br.codesearch.domain.RepoEntry;
import java.util.ArrayList;
import java.util.List;

public class InMemoryRegistry implements RepoRegistry {

    public final List<RepoEntry> entries = new ArrayList<>();

    @Override
    public List<RepoEntry> list() {
        return List.copyOf(entries);
    }

    @Override
    public List<RepoEntry> enabled() {
        return entries.stream().filter(RepoEntry::enabled).toList();
    }
}
