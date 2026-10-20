package br.codesearch.domain;

import java.io.IOException;
import java.net.InetAddress;
import java.util.regex.Pattern;

/** Decide se um host é loopback sem nunca resolver DNS: nome de domínio nunca vale, só "localhost" e IP literal. */
public final class Loopback {

    private static final Pattern IP_LITERAL = Pattern.compile("^(\\d{1,3}(\\.\\d{1,3}){3}|\\[?[0-9A-Fa-f:]+\\]?)$");

    private Loopback() {}

    public static boolean isLoopback(String host) {
        if (host == null) {
            return false;
        }
        if (host.equals("localhost")) {
            return true;
        }
        if (!IP_LITERAL.matcher(host).matches()) {
            return false;
        }
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (IOException e) {
            return false;
        }
    }
}
