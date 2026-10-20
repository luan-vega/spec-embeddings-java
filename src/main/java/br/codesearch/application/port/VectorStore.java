package br.codesearch.application.port;

import br.codesearch.domain.ChunkRecord;
import br.codesearch.domain.EmbeddingProfile;
import br.codesearch.domain.IndexSchema;
import br.codesearch.domain.SegmentRef;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface VectorStore {

    /** Geração ativa ou vazio se ainda não há índice. */
    Optional<IndexSnapshot> openActive();

    /** Monta e valida um segmento sem publicá-lo. {@code dense} tem uma linha por chunk. */
    SegmentRef writeSegment(
            String repoId, String repoRevision, String indexedAt, EmbeddingProfile profile, IndexSchema schema,
            List<ChunkRecord> chunks, float[][] dense, List<String> roots);

    /** Nova geração (ativa + replace - remove) tornada ativa de forma atômica; devolve o id. */
    String publish(EmbeddingProfile profile, IndexSchema schema, List<SegmentRef> replace, List<String> remove);

    String rollback();

    Map<String, Integer> prune(double tmpMaxAgeSeconds);

    Optional<String> previousGenerationId();
}
