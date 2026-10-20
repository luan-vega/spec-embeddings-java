package br.codesearch.application.fakes;

import br.codesearch.application.port.SourceReader;
import br.codesearch.application.port.SourceReaderFactory;
import br.codesearch.domain.BlobEntry;
import br.codesearch.domain.RepoEntry;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Repositórios em memória: caminho para conteúdo, com revisão controlável. */
public class InMemorySources implements SourceReaderFactory {

    public static final class Repo {
        public String revision = "rev1";
        public final Map<String, byte[]> files = new LinkedHashMap<>();

        public Repo put(String path, String content) {
            files.put(path, content.getBytes(StandardCharsets.UTF_8));
            return this;
        }
    }

    private final Map<String, Repo> repos = new HashMap<>();

    public Repo repo(String id) {
        return repos.computeIfAbsent(id, k -> new Repo());
    }

    @Override
    public SourceReader open(RepoEntry entry) {
        Repo repo = repo(entry.repoId());
        return new SourceReader() {
            @Override
            public String revision() {
                return repo.revision;
            }

            @Override
            public List<BlobEntry> listBlobs(List<String> roots) {
                List<BlobEntry> out = new ArrayList<>();
                repo.files.forEach((path, content) -> {
                    if (roots.stream().anyMatch(r -> path.startsWith(r + "/"))) {
                        out.add(new BlobEntry(path, sha(content), "100644"));
                    }
                });
                return out;
            }

            @Override
            public Iterable<Blob> readBlobs(List<String> shas) {
                List<Blob> out = new ArrayList<>();
                for (String sha : shas) {
                    repo.files.values().stream().filter(c -> sha(c).equals(sha)).findFirst()
                            .ifPresent(c -> out.add(new Blob(sha, c)));
                }
                return out;
            }
        };
    }

    private static String sha(byte[] content) {
        return br.codesearch.domain.CanonicalJson.sha256Hex(new String(content, StandardCharsets.ISO_8859_1));
    }
}
