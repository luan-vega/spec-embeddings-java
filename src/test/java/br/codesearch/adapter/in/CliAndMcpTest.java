package br.codesearch.adapter.in;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class CliAndMcpTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path tmp;

    TestApp app() throws Exception {
        TestApp app = new TestApp(tmp.resolve("home"));
        TestApp.gitRepo(tmp.resolve("repo"));
        return app;
    }

    @Test
    void fullCommandLineFlow() throws Exception {
        TestApp app = app();
        assertThat(app.run("policy", "init").code()).isZero();
        TestApp.Result again = app.run("policy", "init");
        assertThat(again.code()).isEqualTo(1);
        assertThat(again.err()).contains("[invalid_request]").contains("--force");
        assertThat(app.run("policy", "init", "--force").code()).isZero();

        TestApp.Result added = app.run("repo", "add", "pagamento", tmp.resolve("repo").toString());
        assertThat(added.out()).contains("pagamento registrado").contains("src/main/java");
        assertThat(app.run("repo", "list").out()).contains("pagamento\thabilitado");

        TestApp.Result indexed = app.run("index");
        assertThat(indexed.code()).isZero();
        assertThat(indexed.out()).contains("pagamento: 3 chunks, 3 embeddings novos").contains("ativa");
        assertThat(app.run("index").out()).contains("nada mudou");

        TestApp.Result text = app.run("search", "juros compostos parcela", "--top-k", "2");
        assertThat(text.code()).isZero();
        assertThat(text.out()).contains("complete").contains("pagamento:src/main/java/br/Pagamento.java");

        JsonNode json = JSON.readTree(app.run("search", "juros compostos", "--json", "--mode", "lexical").out());
        assertThat(json.get("status").asString()).isEqualTo("complete");
        assertThat(json.get("results").get(0).get("symbol").asString()).contains("calcularJurosComposto");
        assertThat(json.get("results").get(0).has("parent_symbol")).isTrue();
        assertThat(json.get("coverage").get("searched").get(0).asString()).isEqualTo("pagamento");

        assertThat(app.run("status").out()).contains("política: válida").contains("pagamento\tindexado\t3 chunks");
        assertThat(JSON.readTree(app.run("status", "--json").out()).get("repos").get(0).get("indexed").asBoolean()).isTrue();
        assertThat(app.run("prune").out()).contains("removidos: 0 gerações");

        TestApp.Result rollback = app.run("rollback");
        assertThat(rollback.code()).isEqualTo(1);
        assertThat(rollback.err()).contains("no previous generation");

        assertThat(app.run("repo", "remove", "pagamento").out()).contains("removido do registro");
        assertThat(app.run("repo", "remove", "pagamento").code()).isEqualTo(1);
    }

    @Test
    void reportsUsageAndErrorsWithoutLeakingInternals() throws Exception {
        TestApp app = app();
        assertThat(app.run().code()).isEqualTo(2);
        assertThat(app.run().out()).contains("uso: code-search");
        assertThat(app.run("--help").code()).isZero();
        for (String[] bad : new String[][] {
            {"nope"}, {"search"}, {"search", "x", "--bogus"}, {"search", "x", "--top-k"}, {"search", "x", "--top-k", "abc"},
            {"repo", "add", "so-um-argumento"}, {"index", "extra"}, {"serve"}}) {
            TestApp.Result r = app.run(bad);
            assertThat(r.code()).as(String.join(" ", bad)).isEqualTo(1);
            assertThat(r.err()).startsWith("erro [invalid_request]");
        }
        TestApp.Result noIndex = app.run("search", "algo");
        assertThat(noIndex.err()).contains("[no_repos_available]");
        assertThat(noIndex.err()).doesNotContain(tmp.toString()).doesNotContain("Exception");
        TestApp.Result notGit = app.run("repo", "add", "x", tmp.toString());
        assertThat(notGit.err()).contains("not a Git repository").doesNotContain(tmp.toString());
    }

    @Test
    void indexWithoutPolicyIndexesNothing() throws Exception {
        TestApp app = app();
        app.run("repo", "add", "pagamento", tmp.resolve("repo").toString());
        TestApp.Result r = app.run("index");
        assertThat(r.code()).isEqualTo(1);
        assertThat(r.out()).contains("nada indexado: policy_missing");
    }

    @Test
    void mcpIsReadOnlyAndSpeaksJsonRpc() throws Exception {
        TestApp app = app();
        app.run("policy", "init");
        app.run("repo", "add", "pagamento", tmp.resolve("repo").toString());
        app.run("index");

        String input = String.join("\n",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\"}}",
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"search_code\",\"arguments\":{\"query\":\"estornar pagamento\",\"mode\":\"lexical\"}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"code_search_status\"}}",
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"index_repo\"}}",
                "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":{\"name\":\"search_code\",\"arguments\":{\"query\":\"x\",\"extra\":1}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"nada/existe\"}",
                "isto não é json",
                "");
        TestApp.Result r = app.runWithInput(input, "mcp");
        assertThat(r.code()).isZero();
        List<JsonNode> replies = r.out().lines().map(l -> JSON.readTree(l)).toList();
        assertThat(replies).as("a notificação não tem resposta").hasSize(8);

        assertThat(replies.get(0).get("result").get("protocolVersion").asString()).isEqualTo("2024-11-05");
        JsonNode tools = replies.get(1).get("result").get("tools");
        assertThat(tools).hasSize(3);
        assertThat(tools.get(2).get("name").asString()).isEqualTo("ask_code");
        for (JsonNode tool : tools) {
            assertThat(tool.get("annotations").get("readOnlyHint").asBoolean()).isTrue();
        }
        JsonNode search = replies.get(2).get("result");
        assertThat(search.get("isError").asBoolean()).isFalse();
        JsonNode payload = JSON.readTree(search.get("content").get(0).get("text").asString());
        assertThat(payload.get("results").get(0).get("symbol").asString()).contains("estornarPagamento");
        assertThat(JSON.readTree(replies.get(3).get("result").get("content").get(0).get("text").asString()).get("repos")).hasSize(1);
        assertThat(replies.get(4).get("result").get("isError").asBoolean()).as("ferramenta desconhecida").isTrue();
        assertThat(replies.get(5).get("result").get("isError").asBoolean()).as("campo desconhecido").isTrue();
        assertThat(replies.get(6).get("error").get("code").asInt()).isEqualTo(-32601);
        assertThat(replies.get(7).get("error").get("code").asInt()).isEqualTo(-32700);
    }

    @Test
    void askAnswersOrAsksForClarificationOnTheCommandLineAndOverMcp() throws Exception {
        TestApp app = app();
        app.run("policy", "init");
        app.run("repo", "add", "pagamento", tmp.resolve("repo").toString());
        app.run("index");

        app.validator.reply("STATUS: ok\nINTENT: locate\nIDENTIFIERS: estornarPagamento\nSEARCH: refund a payment");
        app.answerer.reply("O estorno fica em `Pagamento.estornarPagamento` [1].");
        TestApp.Result text = app.run("ask", "onde fica o estorno?");
        assertThat(text.code()).isZero();
        assertThat(text.out()).contains("O estorno fica em").contains("[1]* pagamento:src/main/java/br/Pagamento.java")
                .contains("fake:fast (validação), fake:strong (resposta)");

        app.validator.reply("STATUS: clarify\nQUESTION: Qual variável?");
        TestApp.Result clarify = app.run("ask", "como mapear esta variável?", "--json");
        assertThat(clarify.code()).isEqualTo(1);
        JsonNode json = JSON.readTree(clarify.out());
        assertThat(json.get("status").asString()).isEqualTo("needs_clarification");
        assertThat(json.get("questions").get(0).asString()).isEqualTo("Qual variável?");

        app.validator.reply("STATUS: ok\nSEARCH: refund a payment");
        app.answerer.reply("Estorno em [1].");
        String input = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"ask_code\","
                + "\"arguments\":{\"question\":\"onde fica o estorno?\",\"top_k\":3}}}\n"
                + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"ask_code\",\"arguments\":{\"query\":\"x\"}}}\n";
        List<JsonNode> replies = app.runWithInput(input, "mcp").out().lines().map(l -> JSON.readTree(l)).toList();
        JsonNode payload = JSON.readTree(replies.get(0).get("result").get("content").get(0).get("text").asString());
        assertThat(payload.get("status").asString()).isEqualTo("answered");
        assertThat(payload.get("sources").get(0).get("cited").asBoolean()).isTrue();
        assertThat(payload.get("sources").get(0).has("text")).isFalse();
        assertThat(replies.get(1).get("result").get("isError").asBoolean()).as("campo desconhecido").isTrue();

        assertThat(app.run("ask").err()).startsWith("erro [invalid_request]");
    }
}
