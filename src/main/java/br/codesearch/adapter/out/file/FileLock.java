package br.codesearch.adapter.out.file;

import br.codesearch.domain.CodeSearchException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

/** Trava exclusiva entre processos sobre um arquivo .lock; falha com {@code busy} se passar do prazo. */
public final class FileLock implements AutoCloseable {

    private final FileChannel channel;
    private final java.nio.channels.FileLock lock;

    private FileLock(FileChannel channel, java.nio.channels.FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static FileLock acquire(Path file, Duration timeout) {
        try {
            Files.createDirectories(file.getParent());
            FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            long deadline = System.nanoTime() + timeout.toNanos();
            while (true) {
                try {
                    java.nio.channels.FileLock lock = channel.tryLock();
                    if (lock != null) {
                        return new FileLock(channel, lock);
                    }
                } catch (OverlappingFileLockException held) {
                    // outro thread desta JVM segura a trava; tenta de novo até o prazo
                }
                if (System.nanoTime() > deadline) {
                    channel.close();
                    throw new CodeSearchException("store_busy", "another operation holds the lock");
                }
                Thread.sleep(25);
            }
        } catch (IOException e) {
            throw new CodeSearchException("storage_error", "could not lock " + file.getFileName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CodeSearchException("store_busy", "interrupted while waiting for the lock");
        }
    }

    @Override
    public void close() {
        try {
            lock.release();
            channel.close();
        } catch (IOException ignored) {
            // a trava some quando o canal fecha
        }
    }
}
