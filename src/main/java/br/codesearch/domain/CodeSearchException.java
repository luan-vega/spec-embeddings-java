package br.codesearch.domain;

/** Erro com código estável, compartilhado por todas as camadas. A mensagem nunca carrega caminhos absolutos. */
public class CodeSearchException extends RuntimeException {

    private final String code;

    public CodeSearchException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
