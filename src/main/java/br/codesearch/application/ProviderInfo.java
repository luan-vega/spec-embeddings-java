package br.codesearch.application;

import java.util.List;

/**
 * Situação de um provedor de IA na configuração atual. {@code missing} lista o que falta (variáveis de ambiente ou
 * modelo), nunca valores: chaves não passam por aqui.
 */
public record ProviderInfo(
        String role, String name, boolean external, boolean selected, String model, String url, List<String> missing) {

    public boolean ready() {
        return missing.isEmpty();
    }
}
