package br.cliente;

public class Cadastro {

    /** Grava uma nova pessoa na base e devolve o seu código. */
    public String registrarCliente(Cliente cliente) {
        String id = UUID.randomUUID().toString();
        base.put(id, cliente);
        return id;
    }

    public void inativarCliente(String clienteId) {
        base.get(clienteId).setAtivo(false);
    }

    public Optional<Cliente> buscarPorDocumento(String documento) {
        return base.values().stream().filter(c -> c.documento().equals(documento)).findFirst();
    }
}
