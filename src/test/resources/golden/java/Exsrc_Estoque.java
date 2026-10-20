package br.estoque;

public class Estoque {

    /** Separa unidades para uma compra em andamento. */
    public boolean reservarItem(String sku, int quantidade) {
        int disponivel = saldo.getOrDefault(sku, 0);
        if (disponivel < quantidade) return false;
        saldo.put(sku, disponivel - quantidade);
        return true;
    }

    public void reporEstoque(String sku, int quantidade) {
        saldo.merge(sku, quantidade, Integer::sum);
    }

    public double calcularGiroEstoque(double vendas, double estoqueMedio) {
        return estoqueMedio == 0 ? 0 : vendas / estoqueMedio;
    }
}
