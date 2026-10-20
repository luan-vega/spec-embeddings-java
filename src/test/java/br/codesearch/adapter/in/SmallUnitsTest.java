package br.codesearch.adapter.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.codesearch.application.BoundedRunner;
import br.codesearch.domain.CodeSearchException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SmallUnitsTest {

    @Test
    void structuredLogKeepsOnlySafeScalars() {
        String line = StructuredLog.format("search", Map.of(
                "request_id", "abc123", "query", "select * from users where password = 'x'", "path", "/home/luan/repo",
                "duration_ms", 12.34567, "results", 3, "ok", true, "nested", Map.of("a", "b")));
        assertThat(line).contains("\"request_id\":\"abc123\"").contains("\"duration_ms\":12.346").contains("\"results\":3");
        assertThat(line).doesNotContain("password").doesNotContain("/home/luan");
        assertThat(line).contains("\"query\":\"<redacted>\"").contains("\"nested\":\"<redacted>\"");
    }

    @Test
    void payloadsRejectMalformedSearchBodies() {
        JsonMapper json = JsonMapper.builder().build();
        for (String body : new String[] {
            "[]", "{}", "{\"query\":1}", "{\"query\":\"x\",\"top_k\":\"3\"}", "{\"query\":\"x\",\"repos\":\"a\"}",
            "{\"query\":\"x\",\"repos\":[1]}", "{\"query\":\"x\",\"path_prefix\":3}", "{\"query\":\"x\",\"mode\":3}",
            "{\"query\":\"x\",\"surprise\":true}"}) {
            assertThatThrownBy(() -> Payloads.parseSearch(json.readTree(body), null)).as(body)
                    .isInstanceOfSatisfying(CodeSearchException.class, e -> assertThat(e.code()).isEqualTo("invalid_request"));
        }
        var q = Payloads.parseSearch(json.readTree("{\"query\":\"juros\",\"repos\":[\"a\"],\"top_k\":5,\"path_prefix\":\"src\"}"), "rid");
        assertThat(q.mode()).isEqualTo("hybrid");
        assertThat(q.topK()).isEqualTo(5);
        assertThat(q.repos()).containsExactly("a");
        assertThat(q.requestId()).isEqualTo("rid");
    }

    @Test
    void runnerRejectsWhenFullAndTimesOut() throws Exception {
        try (BoundedRunner runner = new BoundedRunner(1, 200, 7)) {
            CountDownLatch release = new CountDownLatch(1);
            AtomicReference<Throwable> first = new AtomicReference<>();
            Thread t = new Thread(() -> {
                try {
                    runner.run(() -> {
                        release.await();
                        return "ok";
                    });
                } catch (Throwable e) {
                    first.set(e);
                }
            });
            t.start();
            Thread.sleep(80); // a primeira tarefa ocupa a única vaga
            assertThatThrownBy(() -> runner.run(() -> "x")).isInstanceOfSatisfying(BoundedRunner.Overloaded.class, e -> {
                assertThat(e.retryAfterSeconds()).isEqualTo(7);
                assertThat(e.httpStatus()).isEqualTo(503);
            });
            t.join(2000);
            assertThat(first.get()).isInstanceOfSatisfying(CodeSearchException.class, e -> {
                assertThat(e.code()).isEqualTo("timeout");
                assertThat(e.httpStatus()).isEqualTo(504);
            });
            release.countDown();
            Thread.sleep(80); // a vaga volta só quando a tarefa termina de fato
            assertThat(runner.run(() -> "livre")).isEqualTo("livre");
        }
    }
}
