package br.codesearch.domain;

import java.util.List;

/** O que o núcleo precisa saber de um segmento indexado, sem conhecer o formato de armazenamento. */
public record SegmentInfo(
        String repoId, String repoRevision, String indexedAt, List<String> roots, String profileId, String schemaId,
        int chunks) {}
