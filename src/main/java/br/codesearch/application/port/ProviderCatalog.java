package br.codesearch.application.port;

import br.codesearch.application.ProviderInfo;
import java.util.List;

/** Provedores de IA conhecidos e o estado da configuração de cada um. Não chama nenhum provedor. */
public interface ProviderCatalog {

    List<ProviderInfo> providers();

    /** Se provedores externos estão autorizados (--allow-remote ou CODE_SEARCH_ALLOW_REMOTE=1). */
    boolean allowRemote();
}
