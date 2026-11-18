/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.client.ClientConfig;
import com.unrealtrainmod.client.compat.iris.IrisCompat;
import com.unrealtrainmod.track.mesh.NativeLayoutProfile;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.LoggerFactory;

/** Event-only loading diagnostics; associations follow the lifetime of their source/cache owners. */
public final class NativeTurnoutLoading {
    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger("UnrealTrainMod/TrackLoading");
    private static final AtomicLong IDS = new AtomicLong();
    private static final Map<Object, Trace> OWNERS = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final ThreadLocal<Trace> CURRENT = new ThreadLocal<>();
    private static boolean enabled;
    private static long frame;
    private static String pass = "resource", backend = "cpu";
    private NativeTurnoutLoading() {}

    public static boolean enabled() { return ClientConfig.LOG_NATIVE_TURNOUT_LOADING.getAsBoolean(); }
    static void beginFrame(long value) {
        frame = value;
        boolean active = enabled();
        if (enabled && !active) OWNERS.clear();
        enabled = active;
    }
    static void view(boolean shadow) {
        pass = shadow ? "shadow" : "main";
        backend = IrisCompat.active() ? "iris" : "native";
    }
    public static Trace current() { return enabled() ? CURRENT.get() : null; }
    public static Trace start(String scene, Object model, Object instance) {
        return enabled() ? new Trace(scene, String.valueOf(model), String.valueOf(instance)) : null;
    }
    static Trace owner(Object owner) { return enabled() ? OWNERS.get(owner) : null; }
    static void associate(Object owner, Trace trace) {
        if (trace != null && !trace.closed && enabled()) OWNERS.put(owner, trace);
    }
    static void forget(Object owner) { OWNERS.remove(owner); }
    static void clear() { OWNERS.clear(); }
    static Trace observe(ClientTrackCache.AssemblyEntry entry) {
        if (!enabled()) {
            if (entry.loading != null) { entry.loading.close(); entry.loading = null; }
        } else if (entry.loading == null) {
            entry.loading = start("world", entry.data.modelId(), entry.data.id());
            if (entry.nativeState != null) entry.nativeState.attachLoading(entry.loading);
            if (entry.crossoverState != null) entry.crossoverState.attachLoading(entry.loading);
            for (var group : entry.meshes.values()) for (var mesh : group) associate(mesh, entry.loading);
        }
        return entry.loading;
    }
    public static void failure(Trace trace, Throwable failure) {
        if (trace != null && enabled()) LOGGER.info("Native turnout loading load={} scene={} model={} instance={} event=failed failure={}",
                trace.id,trace.scene,trace.model,trace.instance,failure.toString());
    }
    static Operation open(Trace trace, String name) { return trace == null || !enabled() ? null : trace.open(name); }
    public static Operation gpu(Trace trace, String name) {
        if (trace == null || !enabled()) return null;
        view(IrisCompat.shadowPass());
        return open(trace, name);
    }
    static Binding bind(Trace trace) { return trace == null || trace.closed || !enabled() ? null : new Binding(trace); }
    static Operation firstDraw(Trace trace) { return trace == null || trace.submitted ? null : open(trace,"render/firstDrawSubmission"); }
    static void submitted(Trace trace) { if (trace != null && enabled()) trace.submitted(); }
    static void pending(Object owner) {
        var trace = owner(owner);
        if (trace != null && !trace.submitted) { trace.requestFrame(); trace.pending.add(owner); trace.waitFrame(true); }
    }
    static void ready(Object owner) {
        var trace = owner(owner);
        if (trace != null) { trace.requestFrame(); trace.pending.remove(owner); }
    }
    static void budgetWait(Trace trace) { if (trace != null && enabled()) trace.waitFrame(false); }
    static void geometry(Operation operation, com.unrealtrainmod.track.mesh.RailTrackMeshData data) {
        if (operation == null) return;
        long vertices = 0, triangles = 0;
        for (var batch : data.batches()) { vertices += batch.mesh().colors().length; triangles += batch.mesh().indices().length/3; }
        operation.value("batches", data.batches().size()); operation.value("vertices", vertices); operation.value("triangles", triangles);
    }
    static Trace page(Set<Trace> contributors, long chunk, Object material) {
        if (contributors.isEmpty()) return null;
        var labels = contributors.stream().map(trace -> "load="+trace.id+":"+trace.instance).toList();
        return start("sharedPage", material, "chunk="+net.minecraft.world.level.ChunkPos.getX(chunk)+","+
                net.minecraft.world.level.ChunkPos.getZ(chunk)+" contributors="+labels);
    }

    public static final class Trace implements AutoCloseable {
        private long id = IDS.incrementAndGet(), start = System.nanoTime();
        private final String scene, model, instance;
        private final NativeLayoutProfile profile;
        private final Set<Object> pending = Collections.newSetFromMap(new IdentityHashMap<>());
        private long cpu, submitCpu, longest, waitFrames, budgetFrames, pageFrames;
        private long lastWaitFrame = -1, lastBudgetFrame = -1, lastPageFrame = -1, pendingFrame = -1;
        private long lastActivityFrame = frame, lastActivityEnd = start, crossFrameGap;
        private String longestStage = "none";
        private boolean submitted, closed;
        private Trace(String scene, String model, String instance) {
            this.scene = scene; this.model = model; this.instance = instance;
            profile = new NativeLayoutProfile(this::event);
            LOGGER.info("Native turnout loading load={} scene={} model={} instance={} event=observed", id,scene,model,instance);
        }
        public NativeLayoutProfile profile() { return enabled() && !closed ? profile : null; }
        public Operation open(String name) { return enabled() && !closed ? new Operation(this, name) : null; }
        private void event(NativeLayoutProfile.Event event) {
            if (!enabled() || closed) return;
            if (event.begin()) {
                if (event.parent() == 0) {
                    if (lastActivityFrame != frame) crossFrameGap += Math.max(0,System.nanoTime()-lastActivityEnd);
                    lastActivityFrame = frame;
                }
                LOGGER.info("Native turnout loading load={} scene={} model={} instance={} backend={} pass={} stage={} parent={} name={} event=begin",
                        id, scene, model, instance, renderScene() ? backend : "cpu", renderScene() ? pass : "resource", event.stage(), event.parent(), event.name());
                return;
            }
            if (event.parent() == 0) {
                lastActivityEnd = System.nanoTime();
                if (event.name().equals("render/firstDrawSubmission")) submitCpu += event.nanos();
                else cpu += event.nanos();
            }
            if (event.exclusiveNanos() > longest) { longest = event.exclusiveNanos(); longestStage = event.name(); }
            LOGGER.info("Native turnout loading load={} scene={} model={} instance={} backend={} pass={} stage={} parent={} name={} event=end cpuMs={} selfMs={} values={}",
                    id, scene, model, instance, renderScene() ? backend : "cpu", renderScene() ? pass : "resource", event.stage(), event.parent(), event.name(),
                    event.nanos()/1_000_000.0, event.exclusiveNanos()/1_000_000.0, event.values());
        }
        private boolean renderScene() { return scene.equals("world") || scene.equals("sharedPage"); }
        private void requestFrame() {
            if (pendingFrame != frame) { pendingFrame = frame; pending.clear(); }
        }
        private void waitFrame(boolean page) {
            if (submitted || closed) return;
            if (lastWaitFrame != frame) { lastWaitFrame = frame; waitFrames++; }
            if (page && lastPageFrame != frame) { lastPageFrame = frame; pageFrames++; }
            if (!page && lastBudgetFrame != frame) { lastBudgetFrame = frame; budgetFrames++; }
        }
        private void submitted() {
            if (submitted || closed) return;
            submitted = true;
            long elapsed = System.nanoTime()-start;
            LOGGER.info("Native turnout loading load={} scene={} model={} instance={} backend={} pass={} event=firstSubmit elapsedMs={} prepareCpuMs={} submitCpuMs={} otherWallMs={} crossFrameGapMs={} waitFrames={} budgetWaitFrames={} pageWaitFrames={} pendingRequestedMeshes={} longestSelfStage={} longestSelfCpuMs={} gpuComplete=false",
                    id, scene, model, instance, backend, pass, elapsed/1_000_000.0, cpu/1_000_000.0,
                    submitCpu/1_000_000.0, Math.max(0,elapsed-cpu-submitCpu)/1_000_000.0, crossFrameGap/1_000_000.0, waitFrames, budgetFrames, pageFrames,
                    pendingFrame == frame ? pending.size() : 0, longestStage, longest/1_000_000.0);
            pending.clear();
        }
        void restart(String reason) {
            if (!enabled() || closed || !submitted) return;
            id = IDS.incrementAndGet(); start = System.nanoTime(); cpu = submitCpu = longest = waitFrames = budgetFrames = pageFrames = 0;
            crossFrameGap = 0; lastActivityFrame = frame; lastActivityEnd = start;
            longestStage = "none"; lastWaitFrame = lastBudgetFrame = lastPageFrame = pendingFrame = -1; submitted = false; pending.clear();
            LOGGER.info("Native turnout loading load={} scene={} model={} instance={} event=rebuild reason={}", id,scene,model,instance,reason);
        }
        @Override public void close() {
            closed = true;
            synchronized (OWNERS) { OWNERS.values().removeIf(value -> value == this); }
            pending.clear();
        }
    }
    static final class Binding implements AutoCloseable {
        private final Trace previous;
        private final NativeLayoutProfile.Binding binding;
        private Binding(Trace trace) { previous = CURRENT.get(); CURRENT.set(trace); binding = trace.profile.bind(); }
        @Override public void close() {
            try { binding.close(); }
            finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
        }
    }
    public static final class Operation implements AutoCloseable {
        private final Trace previous;
        private final NativeLayoutProfile.Scope scope;
        private Operation(Trace trace, String name) {
            previous = CURRENT.get(); CURRENT.set(trace); scope = trace.profile.open(name);
        }
        public void value(String name, Object value) { scope.value(name,value); }
        public void failure(Throwable failure) { scope.failure(failure); }
        @Override public void close() {
            try { scope.close(); }
            finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
        }
    }
}
