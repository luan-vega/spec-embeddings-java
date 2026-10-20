package br.codesearch.config;

import br.codesearch.application.port.ChunkDescriber;
import java.util.function.Supplier;

/**
 * Só consulta o provedor quando a indexação usa o descritor: busca, status e serve não dependem dele. Sem descritor
 * escolhido, o id é vazio e a descrição é vazia, e o índice sai igual ao de antes.
 */
final class LazyDescriber implements ChunkDescriber {

    private final Supplier<ChunkDescriber> load;
    private ChunkDescriber inner;
    private boolean loaded;

    LazyDescriber(Supplier<ChunkDescriber> load) {
        this.load = load;
    }

    private synchronized ChunkDescriber get() {
        if (!loaded) {
            inner = load.get();
            loaded = true;
        }
        return inner;
    }

    @Override
    public String describerId() {
        ChunkDescriber d = get();
        return d == null ? "" : d.describerId();
    }

    @Override
    public String describe(String symbol, String code) {
        ChunkDescriber d = get();
        return d == null ? "" : d.describe(symbol, code);
    }

    @Override
    public boolean local() {
        ChunkDescriber d = get();
        return d == null || d.local();
    }
}
