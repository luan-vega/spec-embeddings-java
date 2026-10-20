package br.pagamento;

public class Fatura {

    /** Totaliza tudo o que foi cobrado no mês. */
    public double somarLancamentos(List<Double> lancamentos) {
        double soma = 0;
        for (double l : lancamentos) soma += l;
        return soma;
    }

    public Fatura fecharFatura(String clienteId, YearMonth mes) {
        Fatura f = abertas.remove(clienteId + mes);
        f.fechar();
        return f;
    }

    public byte[] gerarSegundaVia(String faturaId) {
        Fatura f = repositorio.buscar(faturaId);
        return renderizador.pdf(f);
    }
}
