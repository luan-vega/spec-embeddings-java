package br.codesearch.application.port;

import java.util.List;
import java.util.Map;

public interface SparseEncoder {

    String version();

    /** Termo para frequência, determinístico. */
    List<Map<String, Integer>> encodeDocuments(List<String> texts);

    /** Termo para peso, determinístico. */
    Map<String, Double> encodeQuery(String text);
}
