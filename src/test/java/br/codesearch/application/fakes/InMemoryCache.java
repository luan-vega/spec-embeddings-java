package br.codesearch.application.fakes;

import br.codesearch.application.port.EmbeddingCache;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class InMemoryCache implements EmbeddingCache {

    private final Map<String, float[]> vectors = new HashMap<>();

    @Override
    public Map<String, float[]> lookup(List<String> keys) {
        Map<String, float[]> found = new HashMap<>();
        for (String key : keys) {
            if (vectors.containsKey(key)) {
                found.put(key, vectors.get(key));
            }
        }
        return found;
    }

    @Override
    public void add(Map<String, float[]> added) {
        vectors.putAll(added);
    }
}
