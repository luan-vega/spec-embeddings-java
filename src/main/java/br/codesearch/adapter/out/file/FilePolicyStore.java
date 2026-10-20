package br.codesearch.adapter.out.file;

import br.codesearch.domain.ExclusionPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Política de exclusão em JSON no disco. Ausente ou inválida significa "indexa nada". */
public final class FilePolicyStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private FilePolicyStore() {}

    public static ExclusionPolicy load(Path path) {
        byte[] raw;
        try {
            raw = Files.readAllBytes(path);
        } catch (NoSuchFileException e) {
            return ExclusionPolicy.denyAll("policy_missing");
        } catch (IOException e) {
            return ExclusionPolicy.denyAll("policy_unreadable");
        }
        try {
            return ExclusionPolicy.fromDict(JSON.readValue(raw, Object.class));
        } catch (JacksonException e) {
            return ExclusionPolicy.denyAll("policy_invalid");
        }
    }

    public static void writeDefault(Path path) {
        FileSupport.atomicWrite(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(Map.copyOf(ExclusionPolicy.DEFAULT_POLICY)));
    }
}
