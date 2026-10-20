package br.codesearch.adapter.out.file;

import br.codesearch.domain.CodeSearchException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** Escrita atômica e leitura simples de arquivos, compartilhadas pelos adapters de disco. */
public final class FileSupport {

    private FileSupport() {}

    /** Grava num temporário do mesmo diretório e troca pelo destino; leitores nunca veem arquivo pela metade. */
    public static void atomicWrite(Path target, byte[] content) {
        Path tmp = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID().toString().substring(0, 8) + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            replace(tmp, target);
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

    /** No Windows, trocar um arquivo que outro handle está lendo falha por instantes; tenta de novo. */
    static void replace(Path from, Path to) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                try {
                    Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (java.nio.file.FileSystemException e) {
                if (attempt >= 100 || e instanceof java.nio.file.NoSuchFileException) {
                    throw e;
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
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
