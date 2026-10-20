package br.codesearch.application;

import java.util.List;

/** Resultado de uma indexação. {@code status}: published, unchanged ou skipped. */
public record IndexReport(String status, String reason, String generationId, List<RepoReport> repos, List<String> removed) {

    public IndexReport(String status, String reason) {
        this(status, reason, null, List.of(), List.of());
    }

    public static final class RepoReport {
        public final String repoId;
        public String revision = "";
        public int filesSeen;
        public int filesIndexed;
        public int filesSkippedPolicy;
        public int filesSkippedOther;
        public int chunks;
        public int chunksSkippedSecret;
        public int embeddingsNew;
        public int embeddingsCached;
        public boolean unchanged;

        public RepoReport(String repoId) {
            this.repoId = repoId;
        }
    }
}
