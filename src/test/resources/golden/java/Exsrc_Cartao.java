package br.pagamento;

public class Cartao {

    /** Confere se a sequência de dígitos do cartão é legítima. */
    public boolean validarNumeroCartao(String numero) {
        int soma = 0;
        boolean alterna = false;
        for (int i = numero.length() - 1; i >= 0; i--) {
            int d = numero.charAt(i) - '0';
            if (alterna) { d *= 2; if (d > 9) d -= 9; }
            soma += d;
            alterna = !alterna;
        }
        return soma % 10 == 0;
    }

    public String mascararNumero(String numero) {
        return "**** **** **** " + numero.substring(numero.length() - 4);
    }

    public List<Double> parcelarCompra(double total, int parcelas) {
        double parcela = total / parcelas;
        List<Double> lista = new ArrayList<>();
        for (int i = 0; i < parcelas; i++) lista.add(parcela);
        return lista;
    }
}
