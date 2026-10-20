package br.pagamento;

public class Boleto {

    /** Monta o código numérico que o cliente digita para pagar no banco. */
    public String gerarLinhaDigitavel(String codigoBanco, long numero) {
        String base = codigoBanco + String.format("%010d", numero);
        return base + digitoVerificador(base);
    }

    public boolean verificarVencimento(LocalDate vencimento, LocalDate hoje) {
        return hoje.isAfter(vencimento);
    }

    public double calcularMulta(double valor, int diasAtraso) {
        return diasAtraso <= 0 ? 0.0 : valor * 0.02 + valor * 0.001 * diasAtraso;
    }
}
