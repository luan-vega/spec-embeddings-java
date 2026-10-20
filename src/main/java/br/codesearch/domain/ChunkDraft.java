package br.codesearch.domain;

public record ChunkDraft(
        String source, String symbol, String parentSymbol, String className, int startLine, int endLine, String text) {

    /** Texto embedado: cabeçalho com repo, caminho, classe, símbolo e linhas (+ resumo opcional) + código. */
    public String embedText(String repoId, String description) {
        String summary = description == null || description.isEmpty() ? "" : "summary: " + description + "\n";
        return "repo: " + repoId + "\npath: " + source + "\nclass: " + className + "\n"
                + "symbol: " + symbol + "\nlines: " + startLine + "-" + endLine + "\n" + summary + "\n" + text;
    }

    public String embedText(String repoId) {
        return embedText(repoId, "");
    }

    /**
     * Chave do cache de embeddings. Ignora números de linha: código que só mudou de posição reaproveita o vetor.
     * Com descritor, a chave muda junto com ele.
     */
    public String embedKey(String repoId, String describerId) {
        String raw = String.join("\u001f", repoId, source, className, symbol, text);
        if (describerId != null && !describerId.isEmpty()) {
            raw = raw + "\u001f" + describerId;
        }
        return CanonicalJson.sha256Hex(raw);
    }

    public String embedKey(String repoId) {
        return embedKey(repoId, "");
    }

    public String contentHash() {
        return CanonicalJson.sha256Hex(text).substring(0, 16);
    }
}
