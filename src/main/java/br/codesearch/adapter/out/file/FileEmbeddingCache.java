package br.codesearch.adapter.out.file;

import br.codesearch.application.port.EmbeddingCache;
import br.codesearch.domain.CodeSearchException;
import br.codesearch.domain.EmbeddingProfile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cache de embeddings por (perfil, chave de conteúdo). Cada gravação cria um shard novo e imutável; o arquivo de
 * chaves é publicado por último, então um shard só é lido quando os dois arquivos estão completos.
 */
public final class FileEmbeddingCache implements EmbeddingCache {

    private static final int KEY_BYTES = 16;

    private final Path dir;
    private final int dim;

    public FileEmbeddingCache(Path root, EmbeddingProfile profile) {
        this.dir = root.resolve(profile.profileId());
        this.dim = profile.dim();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String keyOf(String hexKey) {
        return hexKey.substring(0, KEY_BYTES * 2);
    }

    @Override
    public Map<String, float[]> lookup(List<String> keys) {
        Map<String, String> wanted = new HashMap<>();
        for (String k : keys) {
            wanted.put(keyOf(k), k);
        }
        Map<String, float[]> found = new HashMap<>();
        if (wanted.isEmpty()) {
            return found;
        }
        List<Path> keyFiles = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "shard-*.keys")) {
            stream.forEach(keyFiles::add);
        } catch (IOException e) {
            return found;
        }
        keyFiles.sort(Comparator.naturalOrder());
        HexFormat hex = HexFormat.of();
        for (Path keysFile : keyFiles) {
            Path vecsFile = keysFile.resolveSibling(keysFile.getFileName().toString().replace(".keys", ".vecs"));
            try {
                byte[] kb = Files.readAllBytes(keysFile);
                byte[] vb = Files.readAllBytes(vecsFile);
                int rows = kb.length / KEY_BYTES;
                if (kb.length % KEY_BYTES != 0 || (long) rows * dim * Float.BYTES != vb.length) {
                    continue;
                }
                ByteBuffer vecs = ByteBuffer.wrap(vb);
                for (int r = 0; r < rows; r++) {
                    String original = wanted.get(hex.formatHex(kb, r * KEY_BYTES, (r + 1) * KEY_BYTES));
                    if (original != null && !found.containsKey(original)) {
                        float[] v = new float[dim];
                        vecs.position(r * dim * Float.BYTES).asFloatBuffer().get(v);
                        found.put(original, v);
                    }
                }
            } catch (IOException e) {
                // shard ilegível: ignora, o vetor será recalculado
            }
        }
        return found;
    }

    @Override
    public void add(Map<String, float[]> vectors) {
        if (vectors.isEmpty()) {
            return;
        }
        List<String> keys = new ArrayList<>(vectors.keySet());
        ByteBuffer vecs = ByteBuffer.allocate(keys.size() * dim * Float.BYTES);
        ByteBuffer ids = ByteBuffer.allocate(keys.size() * KEY_BYTES);
        for (String key : keys) {
            float[] v = vectors.get(key);
            double norm = 0;
            for (float x : v) {
                norm += (double) x * x;
            }
            if (v.length != dim || !Double.isFinite(norm) || Math.sqrt(norm) < 1e-6) {
                throw new CodeSearchException("embedder_invalid_output", "refusing to cache a null or malformed vector");
            }
            vecs.asFloatBuffer().put(0, v);
            vecs.position(vecs.position() + dim * Float.BYTES);
            ids.put(HexFormat.of().parseHex(keyOf(key)));
        }
        String name = "shard-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        FileSupport.atomicWrite(dir.resolve(name + ".vecs"), vecs.array());
        FileSupport.atomicWrite(dir.resolve(name + ".keys"), ids.array());
    }
}
