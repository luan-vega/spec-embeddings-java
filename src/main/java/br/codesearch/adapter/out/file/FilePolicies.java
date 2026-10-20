package br.codesearch.adapter.out.file;

import br.codesearch.application.port.PolicyStore;
import br.codesearch.domain.ExclusionPolicy;
import java.nio.file.Files;
import java.nio.file.Path;

/** {@link PolicyStore} sobre um arquivo JSON. */
public final class FilePolicies implements PolicyStore {

    private final Path path;

    public FilePolicies(Path path) {
        this.path = path;
    }

    @Override
    public ExclusionPolicy load() {
        return FilePolicyStore.load(path);
    }

    @Override
    public boolean exists() {
        return Files.exists(path);
    }

    @Override
    public void writeDefault() {
        FilePolicyStore.writeDefault(path);
    }
}
