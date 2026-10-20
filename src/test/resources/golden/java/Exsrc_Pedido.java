package br.estoque;

public class Pedido {

    /** Soma preço vezes unidades de cada linha da compra. */
    public double calcularTotalPedido(List<ItemPedido> itens) {
        double total = 0;
        for (ItemPedido i : itens) total += i.preco() * i.quantidade();
        return total;
    }

    public boolean cancelarPedido(String pedidoId) {
        Pedido p = pedidos.get(pedidoId);
        if (p.enviado()) return false;
        p.cancelar();
        return true;
    }

    public List<Pedido> listarPedidosAtrasados(LocalDate hoje) {
        return pedidos.values().stream().filter(p -> p.previsao().isBefore(hoje)).toList();
    }
}
