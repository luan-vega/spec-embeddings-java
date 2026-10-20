package br.codesearch.domain;

/** Referência a um segmento já construído, trocada entre indexador e armazenamento. */
public record SegmentRef(String repoId, String segmentId, String manifestSha256) {}
