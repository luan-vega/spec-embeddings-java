package br.cliente;

public class Cliente {

    /** Confirma se o número de documento pessoal é válido. */
    public boolean validarCpf(String documento) {
        if (documento == null || documento.length() != 11) return false;
        return digitosConferem(documento);
    }

    public String formatarNomeCompleto(String nome, String sobrenome) {
        return (nome.trim() + " " + sobrenome.trim()).toUpperCase();
    }

    public int calcularIdade(LocalDate nascimento, LocalDate hoje) {
        return Period.between(nascimento, hoje).getYears();
    }
}
