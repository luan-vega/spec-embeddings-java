package br.codesearch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ConsoleEncodingTest {

    private static byte[] cmdline(String... items) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String item : items) {
            out.writeBytes(item.getBytes(StandardCharsets.UTF_8));
            out.write(0);
        }
        return out.toByteArray();
    }

    private static String ascii(String s) {
        return new String(s.getBytes(StandardCharsets.UTF_8), StandardCharsets.US_ASCII);
    }

    @Test
    void argumentsDecodedAsAsciiAreReadAgainAsUtf8() {
        String[] mangled = {"ask", ascii("como a busca híbrida junta o resultado?"), "--graph"};
        String[] fixed = ConsoleEncoding.recoverArgs(mangled, StandardCharsets.US_ASCII, cmdline(
                "java", "-Dx=1", "-jar", "app.jar", "ask", "como a busca híbrida junta o resultado?", "--graph"));
        assertThat(fixed).containsExactly("ask", "como a busca híbrida junta o resultado?", "--graph");
    }

    @Test
    void cmdlineThatDoesNotMatchTheArgumentsIsIgnored() {
        String[] mangled = {"ask", ascii("ação")};
        String[] fixed = ConsoleEncoding.recoverArgs(mangled, StandardCharsets.US_ASCII, cmdline("java", "ask", "outra", "ação"));
        assertThat(fixed).isSameAs(mangled);
    }

    @Test
    void anItemThatIsNotUtf8StaysAsTheJvmReadIt() {
        byte[] latin1 = {'a', (byte) 0xE7, 'o'};
        byte[] raw = new byte[latin1.length + 1];
        System.arraycopy(latin1, 0, raw, 0, latin1.length);
        String jvm = new String(latin1, StandardCharsets.US_ASCII);
        String[] fixed = ConsoleEncoding.recoverArgs(new String[] {jvm}, StandardCharsets.US_ASCII, raw);
        assertThat(fixed).containsExactly(jvm);
    }

    @Test
    void withoutArgumentsOrWithAShortCmdlineNothingChanges() {
        String[] none = {};
        assertThat(ConsoleEncoding.recoverArgs(none, StandardCharsets.US_ASCII, cmdline("java"))).isSameAs(none);
        String[] two = {"a", "b"};
        assertThat(ConsoleEncoding.recoverArgs(two, StandardCharsets.US_ASCII, cmdline("b"))).isSameAs(two);
    }
}
