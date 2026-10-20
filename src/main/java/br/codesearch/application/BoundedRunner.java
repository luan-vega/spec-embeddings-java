package br.codesearch.application;

import br.codesearch.domain.CodeSearchException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Concorrência limitada: nunca fila infinita. Excedeu o limite, responde {@code overloaded} (503 com retry-after).
 * O timeout libera o cliente (504), mas a tarefa não é cancelável de verdade: a vaga só volta quando ela termina.
 */
public final class BoundedRunner implements AutoCloseable {

    /** {@code overloaded} com a dica de quando tentar de novo. */
    public static final class Overloaded extends CodeSearchException {
        private final int retryAfterSeconds;

        public Overloaded(int retryAfterSeconds) {
            super("overloaded", "the service is busy, retry later");
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public int retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    private final Semaphore slots;
    private final ExecutorService pool;
    private final long timeoutMillis;
    private final int retryAfter;

    public BoundedRunner(int maxConcurrent, long timeoutMillis, int retryAfter) {
        this.slots = new Semaphore(maxConcurrent);
        this.pool = Executors.newFixedThreadPool(maxConcurrent, r -> {
            Thread t = new Thread(r, "code-search");
            t.setDaemon(true);
            return t;
        });
        this.timeoutMillis = timeoutMillis;
        this.retryAfter = retryAfter;
    }

    public <T> T run(Callable<T> task) {
        if (!slots.tryAcquire()) {
            throw new Overloaded(retryAfter);
        }
        Future<T> future;
        try {
            future = pool.submit(() -> {
                try {
                    return task.call();
                } finally {
                    slots.release();
                }
            });
        } catch (RuntimeException e) {
            slots.release();
            throw e;
        }
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new CodeSearchException("timeout", "the request took too long");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CodeSearchException("timeout", "interrupted");
        }
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }
}
