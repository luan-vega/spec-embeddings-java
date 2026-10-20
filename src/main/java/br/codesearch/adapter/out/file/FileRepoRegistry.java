package br.codesearch.adapter.out.file;

import br.codesearch.application.port.RepoCatalog;
import br.codesearch.domain.CodeSearchException;
import br.codesearch.domain.RepoEntry;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Registro de repositórios em {@code repositories.json}. Remover um repo altera só o registro. */
public final class FileRepoRegistry implements RepoCatalog {

    private static final Pattern REPO_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Path path;

    public FileRepoRegistry(Path path) {
        this.path = path;
    }

    private Map<String, RepoEntry> load() {
        byte[] raw;
        try {
            raw = Files.readAllBytes(path);
        } catch (NoSuchFileException e) {
            return new TreeMap<>();
        } catch (java.io.IOException e) {
            throw new CodeSearchException("invalid_request", "repositories.json is unreadable");
        }
        try {
            Map<String, RepoEntry> entries = new TreeMap<>();
            for (JsonNode e : JSON.readTree(raw).get("repositories")) {
                List<String> roots = new ArrayList<>();
                e.get("roots").forEach(r -> roots.add(r.asString()));
                String id = e.get("repo_id").asString();
                entries.put(id, new RepoEntry(id, e.get("path").asString(), List.copyOf(roots),
                        e.get("enabled").asBoolean(), e.has("ref") ? e.get("ref").asString() : "HEAD"));
            }
            return entries;
        } catch (JacksonException | NullPointerException e) {
            throw new CodeSearchException("invalid_request", "repositories.json is invalid");
        }
    }

    private void save(Map<String, RepoEntry> entries) {
        List<Map<String, Object>> repos = new ArrayList<>();
        for (RepoEntry e : new TreeMap<>(entries).values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("repo_id", e.repoId());
            m.put("path", e.path());
            m.put("roots", e.roots());
            m.put("enabled", e.enabled());
            m.put("ref", e.ref());
            repos.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", 1);
        body.put("repositories", repos);
        FileSupport.atomicWrite(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(body));
    }

    private FileLock lock() {
        return FileLock.acquire(path.resolveSibling(path.getFileName() + ".lock"), Duration.ofSeconds(5));
    }

    @Override
    public List<RepoEntry> list() {
        return List.copyOf(load().values());
    }

    @Override
    public List<RepoEntry> enabled() {
        return list().stream().filter(RepoEntry::enabled).toList();
    }

    public RepoEntry get(String repoId) {
        return load().get(repoId);
    }

    @Override
    public RepoEntry add(String repoId, Path repoPath, List<String> roots) {
        if (!REPO_ID.matcher(repoId).matches()) {
            throw new CodeSearchException("invalid_request", "invalid repo_id");
        }
        Path resolved = repoPath.toAbsolutePath().normalize();
        if (!Files.isDirectory(resolved) || !Files.exists(resolved.resolve(".git"))) {
            throw new CodeSearchException("invalid_request", "path is not a Git repository");
        }
        List<String> normalized = roots.stream().map(RepoEntry::normalizeRoot).toList();
        RepoEntry entry = new RepoEntry(repoId, resolved.toString(),
                normalized.isEmpty() ? RepoEntry.DEFAULT_ROOTS : normalized, true, "HEAD");
        try (FileLock ignored = lock()) {
            Map<String, RepoEntry> entries = load();
            if (entries.containsKey(repoId)) {
                throw new CodeSearchException("invalid_request", "repository already registered");
            }
            entries.put(repoId, entry);
            save(entries);
        }
        return entry;
    }

    @Override
    public boolean remove(String repoId) {
        try (FileLock ignored = lock()) {
            Map<String, RepoEntry> entries = load();
            if (entries.remove(repoId) == null) {
                return false;
            }
            save(entries);
            return true;
        }
    }

    @Override
    public void setEnabled(String repoId, boolean enabled) {
        try (FileLock ignored = lock()) {
            Map<String, RepoEntry> entries = load();
            RepoEntry old = entries.get(repoId);
            if (old == null) {
                throw new CodeSearchException("invalid_request", "unknown repository");
            }
            entries.put(repoId, new RepoEntry(old.repoId(), old.path(), old.roots(), enabled, old.ref()));
            save(entries);
        }
    }
}
