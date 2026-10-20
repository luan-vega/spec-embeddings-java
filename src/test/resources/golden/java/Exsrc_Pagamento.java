package br.pagamento;

public class Pagamento {

    /** Quanto um valor rende ao longo dos meses com taxa fixa. */
    public double calcularJurosComposto(double valor, int meses) {
        return valor * Math.pow(1.02, meses) - valor;
    }

    public boolean estornarPagamento(String contaId, double valor) {
        if (valor <= 0) return false;
        saldos.merge(contaId, valor, Double::sum);
        return true;
    }

    public double aplicarDesconto(double total, double percentual) {
        return total - total * percentual / 100.0;
    }
}
