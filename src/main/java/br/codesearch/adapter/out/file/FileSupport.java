package br.codesearch.adapter.out.file;

import br.codesearch.domain.CodeSearchException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** Escrita atômica e leitura simples de arquivos, compartilhadas pelos adapters de disco. */
public final class FileSupport {

    private FileSupport() {}

    /** Grava num temporário do mesmo diretório e troca pelo destino; leitores nunca veem arquivo pela metade. */
    public static void atomicWrite(Path target, byte[] content) {
        Path tmp = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID().toString().substring(0, 8) + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            Files.write(tmp, content);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new CodeSearchException("storage_error", "could not write " + target.getFileName());
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // o temporário órfão é limpo por prune
            }
        }
    }

    public static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
