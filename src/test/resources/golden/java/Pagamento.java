package br.pagamento;

import java.math.BigDecimal;
import java.util.List;

/** Serviço de pagamentos. */
public class Pagamento {

    private static final String PREFIXO = "PG{";
    private final List<String> historico = new java.util.ArrayList<>();

    /** Calcula juros compostos da parcela. */
    public double calcularJurosComposto(double valor, int meses) {
        return valor * Math.pow(1.02, meses) - valor;
    }

    public void estornar(String contaId) {
        if (contaId == null) {
            throw new IllegalArgumentException("conta {inválida}");
        }
        historico.add("estorno " + contaId); // fecha } no comentário
    }

    @Override
    public String toString() {
        return "Pagamento[" + historico.size() + "]";
    }

    static {
        System.setProperty("pagamento.init", "true");
    }

    public static class Boleto {
        public String gerarLinhaDigitavel(String codigo) {
            return codigo.replace(".", "").substring(0, 10);
        }

        public Boleto() {
        }
    }
}
