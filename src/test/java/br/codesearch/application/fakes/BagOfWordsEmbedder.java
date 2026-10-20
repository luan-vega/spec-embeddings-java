package br.codesearch.application.fakes;

import br.codesearch.application.port.Embedder;
import br.codesearch.domain.EmbeddingProfile;
import br.codesearch.domain.SparseTokenizer;
import java.util.ArrayList;
import java.util.List;

/** Embedder de teste: hashing trick sobre os termos. Textos com palavras em comum ficam próximos. */
public class BagOfWordsEmbedder implements Embedder {

    private final EmbeddingProfile profile;
    public final List<String> documents = new ArrayList<>();
    public boolean returnZeros;

    public BagOfWordsEmbedder() {
        this(64);
    }

    public BagOfWordsEmbedder(int dim) {
        this.profile = new EmbeddingProfile("fake-bow", "sha256:fake-bow", dim, "", "", 0);
    }

    @Override
    public EmbeddingProfile profile() {
        return profile;
    }

    private float[] vec(String text) {
        float[] v = new float[profile.dim()];
        for (String term : SparseTokenizer.tokenize(text)) {
            v[Math.floorMod(term.hashCode(), v.length)] += 1;
        }
        double norm = 0;
        for (float x : v) {
            norm += x * x;
        }
        if (norm == 0) {
            v[0] = 1;
            return v;
        }
        for (int i = 0; i < v.length; i++) {
            v[i] /= (float) Math.sqrt(norm);
        }
        return v;
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        documents.addAll(texts);
        List<float[]> out = new ArrayList<>();
        for (String t : texts) {
            out.add(returnZeros ? new float[profile.dim()] : vec(t));
        }
        return out;
    }

    @Override
    public float[] embedQuery(String text) {
        return vec(text);
    }
}
