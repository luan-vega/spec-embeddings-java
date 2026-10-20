package br.codesearch.domain;

import java.util.Map;

public record ChunkRecord(
        String chunkId, String source, String language, String symbol, String parentSymbol, int startLine, int endLine,
        String text, Map<String, Integer> terms) {}
