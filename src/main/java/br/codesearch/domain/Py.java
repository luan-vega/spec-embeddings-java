package br.codesearch.domain;

/** Semântica de strings do Python (str.isspace, strip, len em pontos de código) para manter paridade com a versão Python. */
final class Py {

    private Py() {}

    static boolean isSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '\u0085';
    }

    static String strip(String s) {
        int a = 0;
        int b = s.length();
        while (a < b && isSpace(s.charAt(a))) {
            a++;
        }
        while (b > a && isSpace(s.charAt(b - 1))) {
            b--;
        }
        return s.substring(a, b);
    }

    static String rstrip(String s) {
        int b = s.length();
        while (b > 0 && isSpace(s.charAt(b - 1))) {
            b--;
        }
        return s.substring(0, b);
    }

    /** len() do Python: pontos de código, não unidades UTF-16. */
    static int len(String s) {
        return s.codePointCount(0, s.length());
    }
}
