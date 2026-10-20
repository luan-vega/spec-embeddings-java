package br.codesearch.application.port;

import java.util.List;
import java.util.Map;

public interface EmbeddingCache {

    Map<String, float[]> lookup(List<String> keys);

    void add(Map<String, float[]> vectors);
}
