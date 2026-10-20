package br.cliente;

public class Endereco {

    /** Remove a pontuação do código postal. */
    public String normalizarCep(String cep) {
        return cep.replaceAll("[^0-9]", "");
    }

    public String montarEnderecoCompleto(String rua, int numero, String cidade) {
        return rua + ", " + numero + " - " + cidade;
    }

    public boolean estaNaMesmaCidade(Endereco a, Endereco b) {
        return a.cidade().equalsIgnoreCase(b.cidade());
    }
}
