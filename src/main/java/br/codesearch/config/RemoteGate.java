package br.codesearch.config;

import br.codesearch.domain.CodeSearchException;
import java.io.PrintStream;

/**
 * Porta única para tudo que sai da máquina. Provedor externo só é criado com permissão explícita
 * ({@code --allow-remote} no comando ou CODE_SEARCH_ALLOW_REMOTE=1) e, quando criado, avisa em stderr o que será enviado.
 */
final class RemoteGate {

    private RemoteGate() {}

    static void require(boolean allowed, String kind, String name, String errorCode) {
        if (!allowed) {
            throw new CodeSearchException(errorCode, kind + " '" + name + "' is external: code or queries would leave this machine; "
                    + "pass --allow-remote on this command or set CODE_SEARCH_ALLOW_REMOTE=1 to opt in");
        }
    }

    static void announce(PrintStream err, String kind, String name, String host, String sends) {
        err.println("aviso: " + kind + " '" + name + "' é EXTERNO; " + sends + " saem desta máquina para " + host);
    }
}
