package br.codesearch.adapter.out.git;

import br.codesearch.application.port.SourceReader;
import br.codesearch.domain.BlobEntry;
import br.codesearch.domain.CodeSearchException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Lê os arquivos rastreados da revisão direto do Git (nunca do working tree). */
public final class GitSource implements SourceReader {

    private static final Set<String> REGULAR_MODES = Set.of("100644", "100755"); // 120000 symlink, 160000 submódulo
    private static final int BATCH = 200;

    private final String repo;
    private final String ref;
    private final String git;
    private String resolved;

    public GitSource(Path repoPath, String ref) {
        this(repoPath, ref, "git");
    }

    public GitSource(Path repoPath, String ref, String git) {
        this.repo = repoPath.toString();
        this.ref = ref;
        this.git = git;
    }

    private static CodeSearchException unavailable(String message) {
        return new CodeSearchException("repo_unavailable", message);
    }

    private byte[] run(List<String> args, byte[] stdin) {
        List<String> cmd = new ArrayList<>(List.of(git, "-C", repo));
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().put("LC_ALL", "C");
        try {
            Process p = pb.start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thread drain = Thread.ofVirtual().start(() -> {
                try {
                    p.getInputStream().transferTo(out);
                } catch (IOException ignored) {
                    // processo terminou
                }
            });
            Thread discard = Thread.ofVirtual().start(() -> {
                try {
                    p.getErrorStream().transferTo(java.io.OutputStream.nullOutputStream());
                } catch (IOException ignored) {
                    // processo terminou
                }
            });
            try (var stdinStream = p.getOutputStream()) {
                if (stdin != null) {
                    stdinStream.write(stdin);
                }
            }
            if (!p.waitFor(300, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw unavailable("git timed out");
            }
            drain.join();
            discard.join();
            if (p.exitValue() != 0) {
                throw unavailable("repository is not readable");
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw unavailable("git is not available");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable("interrupted");
        }
    }

    @Override
    public String revision() {
        if (resolved == null) {
            resolved = new String(run(List.of("rev-parse", "--verify", ref + "^{commit}"), null), StandardCharsets.UTF_8).strip();
        }
        return resolved;
    }

    @Override
    public List<BlobEntry> listBlobs(List<String> roots) {
        List<String> args = new ArrayList<>(List.of("ls-tree", "-r", "-z", revision(), "--"));
        args.addAll(roots);
        byte[] out = run(args, null);
        List<BlobEntry> entries = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= out.length; i++) {
            if (i == out.length || out[i] == 0) {
                if (i > start) {
                    String rec = new String(out, start, i - start, StandardCharsets.UTF_8);
                    int tab = rec.indexOf('\t');
                    String[] meta = rec.substring(0, tab).split(" ");
                    if (meta[1].equals("blob") && REGULAR_MODES.contains(meta[0])) {
                        entries.add(new BlobEntry(rec.substring(tab + 1), meta[2], meta[0]));
                    }
                }
                start = i + 1;
            }
        }
        entries.sort(Comparator.comparing(BlobEntry::path));
        return entries;
    }

    @Override
    public Iterable<Blob> readBlobs(List<String> shas) {
        List<Blob> blobs = new ArrayList<>();
        for (int i = 0; i < shas.size(); i += BATCH) {
            List<String> batch = shas.subList(i, Math.min(i + BATCH, shas.size()));
            byte[] data = run(List.of("cat-file", "--batch"), (String.join("\n", batch) + "\n").getBytes(StandardCharsets.UTF_8));
            int pos = 0;
            for (String sha : batch) {
                int eol = pos;
                while (eol < data.length && data[eol] != '\n') {
                    eol++;
                }
                String[] header = new String(data, pos, eol - pos, StandardCharsets.UTF_8).split(" ");
                if (header.length != 3 || !header[1].equals("blob")) {
                    throw unavailable("unexpected git object");
                }
                int size = Integer.parseInt(header[2]);
                int begin = eol + 1;
                byte[] content = new byte[size];
                System.arraycopy(data, begin, content, 0, size);
                blobs.add(new Blob(sha, content));
                pos = begin + size + 1;
            }
        }
        return blobs;
    }
}
