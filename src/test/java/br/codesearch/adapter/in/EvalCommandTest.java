package br.codesearch.adapter.in;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Comando eval de ponta a ponta: dataset e limiares no formato da versão Python, relatório com o hash dos limiares. */
class EvalCommandTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path tmp;

    TestApp indexedApp() throws Exception {
        TestApp app = new TestApp(tmp.resolve("home"));
        TestApp.gitRepo(tmp.resolve("repo"));
        app.run("policy", "init");
        app.run("repo", "add", "pagamento", tmp.resolve("repo").toString());
        assertThat(app.run("index").code()).isZero();
        return app;
    }

    Path file(String name, String content) throws Exception {
        return Files.writeString(tmp.resolve(name), content);
    }

    static final String DATASET = """
            {"id": "uc2-001", "uc": "UC2", "query": "calcularJurosComposto", "relevant": [{"repo": "pagamento", "source": "src/main/java/br/Pagamento.java", "symbol": "Pagamento.calcularJurosComposto"}]}

            {"id": "uc2-002", "uc": "UC2", "query": "estornarPagamento", "relevant": [{"repo": "pagamento", "source": "src/main/java/br/Pagamento.java", "symbol": "Pagamento.estornarPagamento"}]}
            """;

    @Test
    void passingGateReportsMetricsHashAndExitsZero() throws Exception {
        TestApp app = indexedApp();
        String thresholds = """
                {"k": 10, "rules": [
                  {"uc": "UC2", "mode": "hybrid", "metric": "recall@5", "min": 0.95},
                  {"uc": "UC2", "mode": "hybrid", "metric": "mrr", "min": 0.80},
                  {"uc": "UC2", "mode": "lexical", "metric": "latency_p95_ms", "max": 60000}]}
                """;
        Path out = tmp.resolve("report.json");
        TestApp.Result r = app.run("eval", "--dataset", file("d.jsonl", DATASET).toString(),
                "--thresholds", file("t.json", thresholds).toString(), "--out", out.toString());
        assertThat(r.code()).as(r.out() + r.err()).isZero();

        JsonNode report = JSON.readTree(r.out());
        assertThat(report.get("passed").asBoolean()).isTrue();
        assertThat(report.get("failures")).isEmpty();
        assertThat(report.get("k").asInt()).isEqualTo(10);
        assertThat(report.get("cases").asInt()).isEqualTo(2);
        assertThat(report.get("thresholds_sha256").asString()).hasSize(64);
        JsonNode row = report.get("modes").get("hybrid").get("UC2");
        assertThat(row.get("n").asInt()).isEqualTo(2);
        assertThat(row.get("recall@5").asDouble()).isEqualTo(1.0);
        assertThat(row.get("mrr").asDouble()).isEqualTo(1.0);
        assertThat(row.has("recall@10")).isTrue();
        assertThat(row.has("ndcg@10")).isTrue();
        assertThat(row.has("latency_p50_ms")).isTrue();
        assertThat(report.get("modes").has("semantic")).isTrue();
        assertThat(JSON.readTree(Files.readString(out)).get("passed").asBoolean()).isTrue();
    }

    @Test
    void failingGateListsTheRuleWithActualAndExitsOne() throws Exception {
        TestApp app = indexedApp();
        String thresholds = """
                {"rules": [
                  {"uc": "UC2", "mode": "hybrid", "metric": "mrr", "min": 1.5},
                  {"uc": "UC2", "mode": "hybrid", "metric": "ndcg@10", "min_delta_vs": "lexical", "delta": 5},
                  {"uc": "UC9", "mode": "hybrid", "metric": "mrr", "min": 0.1}]}
                """;
        TestApp.Result r = app.run("eval", "--dataset", file("d.jsonl", DATASET).toString(),
                "--thresholds", file("t.json", thresholds).toString());
        assertThat(r.code()).isEqualTo(1);
        JsonNode report = JSON.readTree(r.out());
        assertThat(report.get("passed").asBoolean()).isFalse();
        JsonNode failures = report.get("failures");
        assertThat(failures).hasSize(3);
        assertThat(failures.get(0).get("min").asDouble()).isEqualTo(1.5);
        assertThat(failures.get(0).get("actual").asDouble()).isEqualTo(1.0);
        assertThat(failures.get(1).get("min_delta_vs").asString()).isEqualTo("lexical");
        assertThat(failures.get(1).has("baseline")).isTrue();
        assertThat(failures.get(2).get("reason").asString()).isEqualTo("missing");
        assertThat(failures.get(2).get("actual").isNull()).isTrue();
        assertThat(report.get("k").asInt()).isEqualTo(10);
    }

    @Test
    void invalidInputsAreReportedWithoutLeakingContent() throws Exception {
        TestApp app = indexedApp();
        String ok = file("t.json", "{\"rules\": []}").toString();
        TestApp.Result bad = app.run("eval", "--dataset", file("bad.jsonl", "{\"id\": \"x\", \"segredo\": 1}\n").toString(), "--thresholds", ok);
        assertThat(bad.code()).isEqualTo(1);
        assertThat(bad.err()).contains("[invalid_request]").contains("invalid dataset line 1").doesNotContain("segredo");

        TestApp.Result noRelevant = app.run("eval", "--dataset",
                file("nr.jsonl", "{\"id\": \"x\", \"uc\": \"UC1\", \"query\": \"q\", \"relevant\": []}\n").toString(), "--thresholds", ok);
        assertThat(noRelevant.err()).contains("has no relevant items");

        assertThat(app.run("eval", "--thresholds", ok).err()).contains("requires --dataset and --thresholds");
        assertThat(app.run("eval", "--dataset", tmp.resolve("nao-existe").toString(), "--thresholds", ok).err())
                .contains("cannot read the dataset file").doesNotContain(tmp.toString());
        assertThat(app.run("eval", "--dataset", file("d.jsonl", DATASET).toString(),
                "--thresholds", file("t2.json", "{\"rules\": [{\"uc\": \"UC1\"}]}").toString()).err()).contains("invalid thresholds file");
    }
}
