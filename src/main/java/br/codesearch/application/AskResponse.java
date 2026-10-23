package br.codesearch.application;

import br.codesearch.application.SearchResponse.Coverage;
import br.codesearch.domain.ChunkView;
import java.util.List;
import java.util.Map;

/**
 * Resultado de uma pergunta. {@code status}: answered, not_found (os trechos não respondem), no_results (a busca não achou
 * nada) ou needs_clarification (a pergunta não dá para buscar; {@code questions} diz o que falta).
 */
public record AskResponse(
        String status, String answer, List<String> questions, Plan plan, List<Source> sources, List<String> warnings,
        Coverage coverage, Models models, Map<String, Long> timingsMs, String requestId) {

    /** Como a pergunta foi entendida e transformada em buscas. */
    public record Plan(String intent, List<String> identifiers, String searchText, List<String> queries) {}

    /** Trecho mostrado ao modelo, numerado como nas citações [n] da resposta. */
    public record Source(int n, ChunkView chunk, double score, boolean cited) {}

    public record Models(String validator, String answerer) {}
}
