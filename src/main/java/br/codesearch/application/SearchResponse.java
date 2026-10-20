package br.codesearch.application;

import br.codesearch.domain.ChunkView;
import java.util.List;

public record SearchResponse(
        String status, List<Result> results, Coverage coverage, IndexInfo index, String requestId) {

    public record Result(ChunkView chunk, double score) {}

    public record Unavailable(String repo, String reason) {}

    /** Repositórios pesquisados e os que não puderam ser (unknown_repo, disabled, no_index). */
    public record Coverage(List<String> searched, List<Unavailable> unavailable) {}

    public record IndexInfo(String profileId, String generation) {}
}
