package br.codesearch.application.port;

import br.codesearch.domain.ChunkView;
import br.codesearch.domain.EmbeddingProfile;
import br.codesearch.domain.Filters;
import br.codesearch.domain.Hit;
import br.codesearch.domain.IndexSchema;
import br.codesearch.domain.Location;
import br.codesearch.domain.SegmentInfo;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Visão consistente de uma geração publicada; uma por requisição. */
public interface IndexSnapshot {

    String generationId();

    String createdAt();

    EmbeddingProfile profile();

    IndexSchema schema();

    List<String> repos();

    Optional<SegmentInfo> segmentInfo(String repoId);

    /**
     * Rankings por sinal: chaves {@code dense} e {@code lexical}, ordenados por score decrescente. {@code denseQuery}
     * nulo quando o modo não usa o sinal denso; {@code lexicalTerms} vazio quando não usa o léxico.
     */
    Map<String, List<Hit>> search(
            List<String> repos, float[] denseQuery, Collection<String> lexicalTerms, Filters filters, String mode, int depth);

    Location locate(Hit hit);

    ChunkView record(Hit hit);
}
