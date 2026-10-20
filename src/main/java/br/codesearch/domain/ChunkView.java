package br.codesearch.domain;

/** Chunk indexado como devolvido pela busca. Sem caminhos locais. */
public record ChunkView(
        String repo, String source, String symbol, String parentSymbol, int startLine, int endLine, String language,
        String text, String repoRevision, String indexedAt) {}
