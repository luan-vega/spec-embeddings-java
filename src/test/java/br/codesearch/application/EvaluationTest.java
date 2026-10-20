package br.codesearch.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import br.codesearch.application.Evaluation.Failure;
import br.codesearch.application.Evaluation.Report;
import br.codesearch.application.Evaluation.Rule;
import br.codesearch.application.Evaluation.Thresholds;
import br.codesearch.domain.CodeSearchException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Métricas conferidas à mão com os mesmos números da versão Python. */
class EvaluationTest {

    static final List<Boolean> FLAGS = List.of(false, true, false, true);

    @Test
    void metricsMatchHandComputedValues() {
        assertThat(Evaluation.recallAtK(FLAGS, 2, 1)).isZero();
        assertThat(Evaluation.recallAtK(FLAGS, 2, 5)).isEqualTo(1.0);
        assertThat(Evaluation.recallAtK(FLAGS, 0, 5)).isZero();
        assertThat(Evaluation.reciprocalRank(FLAGS)).isEqualTo(0.5);
        assertThat(Evaluation.reciprocalRank(List.of(false, false))).isZero();
        // dcg = 1/log2(3) + 1/log2(5); ideal = 1 + 1/log2(3)
        assertThat(Evaluation.ndcgAtK(FLAGS, 2, 10)).isCloseTo(0.65090, within(1e-4));
        assertThat(Evaluation.ndcgAtK(List.of(true), 1, 10)).isEqualTo(1.0);
        assertThat(Evaluation.ndcgAtK(FLAGS, 0, 10)).isZero();
    }

    @Test
    void percentileIsNearestRank() {
        List<Double> ten = List.of(10.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0);
        assertThat(Evaluation.percentile(ten, 50)).isEqualTo(5.0);
        assertThat(Evaluation.percentile(ten, 95)).isEqualTo(10.0);
        assertThat(Evaluation.percentile(List.of(), 95)).isZero();
    }

    @Test
    void caseWithoutRelevantItemsIsRejected() {
        assertThatThrownBy(() -> new Evaluation.Case("c1", "UC1", "q", List.of()))
                .isInstanceOfSatisfying(CodeSearchException.class, e -> assertThat(e.code()).isEqualTo("invalid_request"));
    }

    static Report report() {
        return new Report(10, 4, Map.of(
                "hybrid", Map.of("UC1", Map.<String, Number>of("ndcg@10", 0.60, "latency_p95_ms", 120.0)),
                "lexical", Map.of("UC1", Map.<String, Number>of("ndcg@10", 0.55))));
    }

    static Rule rule(String mode, String metric, Double min, Double max, String vs, Double delta) {
        return new Rule("UC1", mode, metric, min, max, vs, delta, Map.of());
    }

    @Test
    void thresholdRulesCheckMinMaxAndDeltaAndMissingMetrics() {
        List<Rule> rules = List.of(
                rule("hybrid", "ndcg@10", 0.5, null, null, null),
                rule("hybrid", "latency_p95_ms", null, 300.0, null, null),
                rule("hybrid", "ndcg@10", null, null, "lexical", 0.04));
        assertThat(Evaluation.checkThresholds(report(), new Thresholds(10, rules, "x"))).isEmpty();

        List<Rule> bad = List.of(
                rule("hybrid", "ndcg@10", 0.7, null, null, null),
                rule("hybrid", "latency_p95_ms", null, 100.0, null, null),
                rule("hybrid", "ndcg@10", null, null, "lexical", 0.10),
                rule("semantic", "ndcg@10", 0.1, null, null, null),
                rule("hybrid", "ndcg@10", null, null, "semantic", 0.0));
        List<Failure> failures = Evaluation.checkThresholds(report(), new Thresholds(10, bad, "x"));
        assertThat(failures).hasSize(5);
        assertThat(failures.get(0).actual()).isEqualTo(0.60);
        assertThat(failures.get(2).baseline()).isEqualTo(0.55);
        assertThat(failures.get(3).reason()).isEqualTo("missing");
        assertThat(failures.get(4).baseline()).isNull();
    }
}
