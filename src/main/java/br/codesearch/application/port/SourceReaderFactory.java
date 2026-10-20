package br.codesearch.application.port;

import br.codesearch.domain.RepoEntry;

@FunctionalInterface
public interface SourceReaderFactory {

    SourceReader open(RepoEntry entry);
}
