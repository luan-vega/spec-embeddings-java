package br.codesearch.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class CodeGraphTest {

    static final String PEDIDO_SERVICE = """
            package br.pedidos;

            import br.pagamento.PagamentoGateway;

            public class PedidoService {
                private final PagamentoGateway gateway;
                private final FreteCalculadora frete;

                /** Fecha o pedido e cobra o cliente. */
                public Recibo finalizar(Pedido pedido) {
                    double total = pedido.total() + frete.calcular(pedido);
                    // gateway.estornar(pedido) não deve virar aresta: está em comentário
                    String log = "validarLimite(x)";
                    return gateway.cobrar(total);
                }
            }
            """;

    static final String FRETE = """
            package br.pedidos;

            public class FreteCalculadora {
                public double calcular(Pedido pedido) {
                    return pedido.peso() * 1.5;
                }
            }
            """;

    static final String GATEWAY = """
            package br.pagamento;

            public interface PagamentoGateway {
                Recibo cobrar(double valor);

                Recibo estornar(Pedido pedido);
            }
            """;

    static final String CARTAO = """
            package br.pagamento;

            public class CartaoGateway extends GatewayBase<Recibo> implements PagamentoGateway, java.io.Serializable {
                public Recibo cobrar(double valor) {
                    validarLimite(valor);
                    return new Recibo(valor);
                }

                public Recibo estornar(Pedido pedido) {
                    return null;
                }

                private void validarLimite(double valor) {
                    if (valor > 1000) {
                        throw new IllegalStateException("limite");
                    }
                }
            }
            """;

    static final String BASE = """
            package br.pagamento;

            public abstract class GatewayBase<T> {
                protected void auditar(String evento) {
                    System.out.println(evento);
                }
            }
            """;

    static List<CodeGraph.Input> inputs(Map<String, Map<String, String>> repos) {
        List<CodeGraph.Input> out = new ArrayList<>();
        repos.forEach((repo, files) -> {
            int row = 0;
            for (var file : files.entrySet()) {
                for (ChunkDraft d : JavaChunker.chunkJava(file.getKey(), file.getValue())) {
                    out.add(new CodeGraph.Input(repo, row++, d.source(), d.symbol(), d.parentSymbol(), d.startLine(),
                            d.endLine(), d.text()));
                }
            }
        });
        return out;
    }

    static final List<CodeGraph.Input> INPUTS = inputs(Map.of(
            "svc-pedidos", new java.util.TreeMap<>(Map.of(
                    "src/br/pedidos/PedidoService.java", PEDIDO_SERVICE, "src/br/pedidos/FreteCalculadora.java", FRETE)),
            "svc-pagamento", new java.util.TreeMap<>(Map.of(
                    "src/br/pagamento/PagamentoGateway.java", GATEWAY, "src/br/pagamento/CartaoGateway.java", CARTAO,
                    "src/br/pagamento/GatewayBase.java", BASE))));
    static final CodeGraph GRAPH = CodeGraph.build(INPUTS);

    static int id(String repo, String symbol) {
        for (int i = 0; i < INPUTS.size(); i++) {
            if (INPUTS.get(i).repo().equals(repo) && INPUTS.get(i).symbol().equals(symbol)) {
                return i;
            }
        }
        throw new AssertionError("no chunk " + symbol);
    }

    /** Vizinhos em um passo, como "símbolo=via". */
    static Map<String, String> neighbors(String repo, String symbol) {
        return GRAPH.expand(List.of(id(repo, symbol)), 1, 50, n -> true).stream()
                .collect(Collectors.toMap(n -> n.info().symbol(), CodeGraph.Neighbor::via, (a, b) -> a));
    }

    @Test
    void callsResolveThroughFieldTypesAndReachImplementationsInOtherRepos() {
        Map<String, String> from = neighbors("svc-pedidos", "PedidoService.finalizar");

        assertThat(from).containsEntry("PagamentoGateway.cobrar", "called_by") // tipo do campo gateway
                .containsEntry("CartaoGateway.cobrar", "called_by") // implementação da interface, outro repositório
                .containsEntry("FreteCalculadora.calcular", "called_by")
                .containsEntry("PedidoService", "class_of");
        // comentários e strings não contam; Pedido e Recibo não estão indexados
        assertThat(from).doesNotContainKeys("PagamentoGateway.estornar", "CartaoGateway.estornar", "CartaoGateway.validarLimite");
    }

    @Test
    void edgesWorkInBothDirections() {
        assertThat(neighbors("svc-pagamento", "CartaoGateway.cobrar"))
                .containsEntry("PedidoService.finalizar", "calls")
                .containsEntry("CartaoGateway.validarLimite", "called_by")
                .containsEntry("CartaoGateway", "class_of");
        assertThat(neighbors("svc-pagamento", "CartaoGateway"))
                .containsEntry("PagamentoGateway", "supertype_of")
                .containsEntry("GatewayBase", "supertype_of")
                .containsEntry("CartaoGateway.cobrar", "member_of");
        assertThat(neighbors("svc-pagamento", "PagamentoGateway"))
                .containsEntry("CartaoGateway", "subtype_of")
                .containsEntry("PedidoService", "uses"); // o campo gateway
    }

    @Test
    void expansionRanksNodesLinkedToSeveralSeedsFirstAndSkipsSeeds() {
        int finalizar = id("svc-pedidos", "PedidoService.finalizar");
        int cobrarCartao = id("svc-pagamento", "CartaoGateway.cobrar");
        var result = GRAPH.expand(List.of(finalizar, cobrarCartao), 1, 3, n -> true);

        assertThat(result).hasSize(3);
        assertThat(result).extracting(n -> n.info().symbol())
                .doesNotContain("PedidoService.finalizar", "CartaoGateway.cobrar")
                .first().isEqualTo("PagamentoGateway.cobrar"); // chamado por finalizar e supertipo de cobrar do cartão
        assertThat(result.get(0).seed()).isZero();

        var onlyPedidos = GRAPH.expand(List.of(finalizar), 1, 50, n -> n.repo().equals("svc-pedidos"));
        assertThat(onlyPedidos).allMatch(n -> n.info().repo().equals("svc-pedidos"));
    }

    @Test
    void twoHopsReachTheCalleesOfTheCallees() {
        int finalizar = id("svc-pedidos", "PedidoService.finalizar");
        assertThat(GRAPH.expand(List.of(finalizar), 1, 50, n -> true)).extracting(n -> n.info().symbol())
                .doesNotContain("CartaoGateway.validarLimite");
        var twoHops = GRAPH.expand(List.of(finalizar), 2, 50, n -> true);
        var limite = twoHops.stream().filter(n -> n.info().symbol().equals("CartaoGateway.validarLimite")).findFirst();
        assertThat(limite).hasValueSatisfying(n -> {
            assertThat(n.hops()).isEqualTo(2);
            assertThat(n.via()).isEqualTo("called_by > called_by");
        });
    }

    @Test
    void linksDescribeHowTheContextSnippetsRelate() {
        List<Integer> context = List.of(id("svc-pedidos", "PedidoService.finalizar"), id("svc-pagamento", "PagamentoGateway.cobrar"),
                id("svc-pagamento", "CartaoGateway"), id("svc-pagamento", "PagamentoGateway"));
        assertThat(GRAPH.links(context)).contains(
                new CodeGraph.Link(0, 1, CodeGraph.Relation.CALLS),
                new CodeGraph.Link(2, 3, CodeGraph.Relation.INHERITS),
                new CodeGraph.Link(1, 3, CodeGraph.Relation.MEMBER_OF));
        assertThat(CodeGraph.Relation.CALLS.verb).isEqualTo("calls");
    }

    @Test
    void findLocatesChunksByPosition() {
        CodeGraph.Input in = INPUTS.get(id("svc-pagamento", "CartaoGateway.cobrar"));
        assertThat(GRAPH.find(in.repo(), in.source(), in.symbol(), in.startLine(), in.endLine())).hasValue(
                id("svc-pagamento", "CartaoGateway.cobrar"));
        assertThat(GRAPH.find("svc-pagamento", in.source(), in.symbol(), 999, 1000)).isEmpty();
        assertThat(GRAPH.size()).isEqualTo(INPUTS.size());
        assertThat(GRAPH.edgeCount()).isPositive();
    }

    @Test
    void ambiguousNamesAreNotLinkedToEverything() {
        StringBuilder many = new StringBuilder();
        List<CodeGraph.Input> in = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String cls = "Repo" + i;
            in.add(new CodeGraph.Input("r", in.size(), cls + ".java", cls, "", 1, 1, "public class " + cls + " {"));
            in.add(new CodeGraph.Input("r", in.size(), cls + ".java", cls + ".salvar", cls, 2, 4,
                    "void salvar() { }"));
            many.append("Repo").append(i).append(" r").append(i).append(";\n");
        }
        // sem tipo conhecido para x: cita as 6 classes, então salvar teria 6 alvos e é descartado
        in.add(new CodeGraph.Input("r", in.size(), "Uso.java", "Uso.run", "Uso", 1, 9,
                "void run() {\n" + many + "x.salvar();\n}"));
        CodeGraph g = CodeGraph.build(in);
        var callees = g.expand(List.of(in.size() - 1), 1, 50, n -> true).stream()
                .filter(n -> n.via().equals("called_by")).toList();
        assertThat(callees).isEmpty();
    }

    @Test
    void supertypesIgnoreGenericsAndRecordComponents() {
        assertThat(CodeGraph.parseSupertypes(" extends Base<Map<String, List<X>>> implements a.b.Porta, Outra<T> "))
                .containsExactly("Base", "Porta", "Outra");
        assertThat(CodeGraph.parseSupertypes("(String id, List<Item> itens) implements Comparable<Pedido> "))
                .containsExactly("Comparable");
        assertThat(CodeGraph.parseSupertypes(" ")).isEmpty();
    }
}
