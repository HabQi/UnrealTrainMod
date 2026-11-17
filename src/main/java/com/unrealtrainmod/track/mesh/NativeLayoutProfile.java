/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.track.mesh;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Optional, call-scoped CPU diagnostics. Contains no client configuration or rendering dependencies. */
public final class NativeLayoutProfile {
    public record Event(long stage, long parent, String name, boolean begin, long nanos,
                        long exclusiveNanos, Map<String, Object> values) {}
    private static final ThreadLocal<NativeLayoutProfile> ACTIVE = new ThreadLocal<>();
    private final Consumer<Event> sink;
    private Scope current;
    private long sequence;

    public NativeLayoutProfile(Consumer<Event> sink) { this.sink = sink; }

    public Binding bind() {
        var previous = ACTIVE.get(); ACTIVE.set(this); return new Binding(previous);
    }
    public static final class Binding implements AutoCloseable {
        private final NativeLayoutProfile previous;
        private Binding(NativeLayoutProfile previous) { this.previous = previous; }
        @Override public void close() { if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous); }
    }

    public Scope open(String name) {
        var previous = ACTIVE.get();
        ACTIVE.set(this);
        return new Scope(name, previous);
    }

    public static Scope stage(String name) {
        var profile = ACTIVE.get();
        return profile == null ? null : profile.open(name);
    }

    public static Scope current() {
        var profile = ACTIVE.get();
        return profile == null ? null : profile.current;
    }

    public final class Scope implements AutoCloseable {
        private final long id = ++sequence;
        private final Scope parent = current;
        private final NativeLayoutProfile previous;
        private final String name;
        private final Map<String, Object> values = new LinkedHashMap<>();
        private final long start;
        private long children;
        private boolean closed;

        private Scope(String name, NativeLayoutProfile previous) {
            this.name = name; this.previous = previous;
            current = this;
            sink.accept(new Event(id, parent == null ? 0 : parent.id, name, true, 0, 0, Map.of()));
            start = System.nanoTime();
        }
        public void value(String key, Object value) { values.put(key, value); }
        public void count(String key) { values.merge(key, 1L, (a, b) -> (Long)a + (Long)b); }
        public void failure(Throwable failure) { value("failure", failure.toString()); }
        @Override public void close() {
            if (closed) return;
            closed = true;
            long elapsed = System.nanoTime() - start;
            if (parent != null) parent.children += elapsed;
            current = parent;
            if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous);
            sink.accept(new Event(id, parent == null ? 0 : parent.id, name, false,
                    elapsed, Math.max(0, elapsed - children), values));
        }
    }
}
