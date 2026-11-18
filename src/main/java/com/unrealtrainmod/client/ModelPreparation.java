/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import org.slf4j.LoggerFactory;

/** Workers own only snapshots. Publication, disposal and cache mutation belong to the client thread. */
public final class ModelPreparation {
    private static final long SOFT_LIMIT = 64L * 1024 * 1024;
    private static final Object SPACE = new Object();
    private static final AtomicLong RETAINED = new AtomicLong(), CPU = new AtomicLong(), WAIT = new AtomicLong();
    private static final AtomicLong DISCARDED = new AtomicLong();
    private static final ConcurrentLinkedQueue<Ticket<?>> COMPLETED = new ConcurrentLinkedQueue<>();
    private static final ThreadPoolExecutor GEOMETRY = worker("UTM model geometry");
    private static final ThreadPoolExecutor SCRIPTS = worker("UTM script compiler");
    private static long lastLog = System.nanoTime();

    private ModelPreparation() {}

    private static ThreadPoolExecutor worker(String name) {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), task -> {
            var thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    public static <T> Ticket<T> geometry(Supplier<T> build, ToLongFunction<T> bytes, Consumer<T> dispose) {
        return submit(GEOMETRY, build, bytes, dispose);
    }

    public static <T> Ticket<T> script(Supplier<T> build, Consumer<T> dispose) {
        return submit(SCRIPTS, build, ignored -> 0L, dispose);
    }

    private static <T> Ticket<T> submit(ThreadPoolExecutor worker, Supplier<T> build,
                                       ToLongFunction<T> bytes, Consumer<T> dispose) {
        var ticket = new Ticket<T>(worker, build, bytes, dispose);
        try { worker.execute(ticket); return ticket; }
        catch (RejectedExecutionException exception) { return null; }
    }

    /** A ticket is also a lease on completed staging memory. Close after adoption/upload or cancellation. */
    public static final class Ticket<T> implements AutoCloseable, Runnable {
        private final Supplier<T> build;
        private final ToLongFunction<T> size;
        private final Consumer<T> dispose;
        private final ThreadPoolExecutor owner;
        private final long requested = System.nanoTime();
        private volatile boolean closed;
        private T value;
        private Throwable failure;
        private long bytes;
        private boolean published;
        private Runnable releaseInput = () -> {};
        private Runnable available = () -> {};

        private Ticket(ThreadPoolExecutor owner, Supplier<T> build, ToLongFunction<T> size, Consumer<T> dispose) {
            this.owner = owner;
            this.build = build; this.size = size; this.dispose = dispose;
        }

        @Override public void run() {
            try {
                synchronized (SPACE) {
                    while (!closed && RETAINED.get() >= SOFT_LIMIT) SPACE.wait();
                }
                if (closed) return;
                WAIT.addAndGet(System.nanoTime() - requested);
                long start = System.nanoTime();
                try { value = build.get(); bytes = size.applyAsLong(value); RETAINED.addAndGet(bytes); }
                catch (Throwable exception) { failure = exception; }
                finally { CPU.addAndGet(System.nanoTime() - start); }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                failure = exception;
            } finally {
                COMPLETED.add(this);
            }
        }

        public boolean ready() { return published && !closed; }
        public Ticket<T> onRelease(Runnable action) { releaseInput = action; return this; }
        public Ticket<T> whenReady(Runnable action) { available = action; return this; }
        public T value() {
            if (failure != null) throw new IllegalStateException("Model preparation failed", failure);
            return value;
        }
        /** Transfer ordinary CPU geometry; unlike staging buffers it no longer belongs to this ticket. */
        public T take() {
            T result = value();
            value = null;
            close();
            return result;
        }
        @Override public void close() {
            if (closed) return;
            closed = true;
            if (owner.remove(this)) {
                published = true; DISCARDED.incrementAndGet(); release();
            } else if (published) release();
            synchronized (SPACE) { SPACE.notifyAll(); }
        }
        private void release() {
            try { if (value != null) dispose.accept(value); }
            finally {
                releaseInput.run(); releaseInput = () -> {};
                value = null;
                RETAINED.addAndGet(-bytes); bytes = 0;
                synchronized (SPACE) { SPACE.notifyAll(); }
            }
        }
    }

    public static void beginFrame() {
        Ticket<?> ticket;
        while ((ticket = COMPLETED.poll()) != null) {
            ticket.published = true;
            if (ticket.closed) { DISCARDED.incrementAndGet(); ticket.release(); }
            else ticket.available.run();
        }
        if (System.nanoTime() - lastLog < 5_000_000_000L) return;
        long cpu = CPU.getAndSet(0), wait = WAIT.getAndSet(0), discarded = DISCARDED.getAndSet(0);
        String uploads = ModelUploads.statistics();
        if (ClientConfig.LOG_TRACK_RENDER_STATS.getAsBoolean() || ClientConfig.LOG_VEHICLE_INSTANCING_STATS.getAsBoolean()) {
            LoggerFactory.getLogger("UnrealTrainMod/Preparation").info(
                    "Model preparation: geometryPending={} scriptPending={} staging={}B cpu={}ms queueWait={}ms discarded={} | {}",
                    GEOMETRY.getQueue().size(), SCRIPTS.getQueue().size(), RETAINED.get(),
                    cpu / 1_000_000.0, wait / 1_000_000.0, discarded, uploads);
        }
        lastLog = System.nanoTime();
    }

    public static void shutdown() {
        for (var queued : GEOMETRY.shutdownNow()) cancelQueued((Ticket<?>)queued);
        for (var queued : SCRIPTS.shutdownNow()) cancelQueued((Ticket<?>)queued);
        beginFrame();
        ModelUploads.clear();
    }
    private static void cancelQueued(Ticket<?> ticket) {
        ticket.published = true;
        if (ticket.closed) ticket.release(); else ticket.close();
    }
}
