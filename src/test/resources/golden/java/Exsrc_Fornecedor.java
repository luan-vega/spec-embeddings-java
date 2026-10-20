package br.estoque;

public class Fornecedor {

    /** Nota de confiabilidade de quem vende para a loja. */
    public double avaliarFornecedor(int entregasNoPrazo, int entregasTotais) {
        return entregasTotais == 0 ? 0 : 100.0 * entregasNoPrazo / entregasTotais;
    }

    public String gerarOrdemCompra(String fornecedorId, String sku, int quantidade) {
        String numero = "OC-" + contador.incrementAndGet();
        ordens.put(numero, new Ordem(fornecedorId, sku, quantidade));
        return numero;
    }

    public double compararCotacoes(List<Double> precos) {
        return Collections.min(precos);
    }
}
