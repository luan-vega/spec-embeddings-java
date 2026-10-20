package br.codesearch.application.port;

import br.codesearch.domain.BlobEntry;
import java.util.List;

/** Lê arquivos de uma revisão fixa de um repositório. */
public interface SourceReader {

    record Blob(String sha, byte[] content) {}

    String revision();

    List<BlobEntry> listBlobs(List<String> roots);

    Iterable<Blob> readBlobs(List<String> shas);
}
