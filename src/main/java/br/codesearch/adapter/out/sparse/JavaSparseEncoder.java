package br.codesearch.adapter.out.sparse;

import br.codesearch.application.port.SparseEncoder;
import br.codesearch.domain.SparseTokenizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Encoder esparso para identificadores Java (camelCase e snake_case), sobre o tokenizador do domain. */
public final class JavaSparseEncoder implements SparseEncoder {

    @Override
    public String version() {
        return SparseTokenizer.TOKENIZER_VERSION;
    }

    @Override
    public List<Map<String, Integer>> encodeDocuments(List<String> texts) {
        List<Map<String, Integer>> out = new ArrayList<>(texts.size());
        for (String text : texts) {
            out.add(SparseTokenizer.encodeDocument(text));
        }
        return out;
    }

    @Override
    public Map<String, Double> encodeQuery(String text) {
        return SparseTokenizer.encodeQuery(text);
    }
}
