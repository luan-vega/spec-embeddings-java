package br.cliente;

public class Contato {

    /** Verifica se o endereço eletrônico tem formato correto. */
    public boolean validarEmail(String email) {
        return email != null && email.matches("^[^@\\s]+@[^@\\s]+\\.[a-z]{2,}$");
    }

    public String formatarTelefone(String ddd, String numero) {
        return "(" + ddd + ") " + numero.substring(0, 5) + "-" + numero.substring(5);
    }

    public String mascararEmail(String email) {
        int arroba = email.indexOf('@');
        return email.charAt(0) + "***" + email.substring(arroba);
    }
}
