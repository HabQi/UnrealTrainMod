/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.compat.iris;

import com.unrealtrainmod.client.ClientConfig;
import com.unrealtrainmod.entity.TrainEntity;
import java.util.Arrays;
import java.util.Locale;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** CPU work and owned uploads, separated from deferred submissions and GPU timings. */
public final class IrisRenderStats {
    public static final int VEHICLE = 0, TRACK = 1;
    private static final Logger LOGGER = LoggerFactory.getLogger("UnrealTrainMod/Iris");
    private static final int SCOPES = 17, SETUPS = 18, APPLIES = 19, MATRICES = 20, BINDS = 21,
            GROUPS = 22, GROUP_CPU = 23, CAPTURE_CPU = 24, DIRECT_CPU = 25, RESTORE_CPU = 26,
            POSE_SNAPSHOTS = 27, POSE_VALUES = 28, POSE_RUNS = 29, POSE_GROUPS = 30, LIGHT_GROUPS = 31,
            RANGE_CPU = 32, RANGE_BEFORE = 33, RANGE_AFTER = 34, MERGE_CPU = 35,
            BIND_REQUESTS = 36, COMMAND_GROWTH = 37, PREPARED_GROWTH = 38, VIEW_POSE_GROWTH = 39,
            OPAQUE_CALLS = 40, MULTI_CALLS = 41, MULTI_RANGES = 42;
    private static final Window[] WINDOWS = {new Window("Vehicle"), new Window("Track")};
    private static Sample current;

    private static final class Window {
        final String name;
        final long[][] values = new long[2][43];
        final long[] viewFrame = {-1, -1};
        final RangeSample[] ranges = new RangeSample[2];
        final IrisGpuTimer[] timers = new IrisGpuTimer[3];
        long id;
        long start = System.nanoTime();
        Window(String name) { this.name = name; }
        void reset() {
            for (var slot : values) Arrays.fill(slot, 0);
            Arrays.fill(viewFrame, -1);
            Arrays.fill(ranges, null);
            start = System.nanoTime();
            id++;
            for (var timer : timers) if (timer != null) timer.resetWindow();
        }
        void release() {
            for (var timer : timers) if (timer != null) timer.close();
            Arrays.fill(timers, null);
            reset();
        }
        IrisGpuTimer timer(int pass) {
            if (timers[pass] == null) timers[pass] = new IrisGpuTimer();
            return timers[pass];
        }
        String gpu(int pass) {
            return timers[pass] == null ? "noSamples samples=0 avg=n/a max=n/a" : timers[pass].summary();
        }
        void logState(int slot) {
            var v = values[slot];
            if (v[0] == 0) return;
            LOGGER.info("Vehicle Iris state [{}] | perView scopes={} opaqueGroups={} stateSetups={} shaderApplies={} matrixChanges={} bufferBinds={} bufferBindRequests={} bufferBindsSaved={} "
                            + "| opaque poseSnapshots={} poseValues={} poseRunsBefore={} poseGroups={} lightGroups={} poseRunsSaved={} "
                            + "rangeCallsBefore={} rangeCallsAfter={} rangeCallsSaved={} "
                            + "opaqueDrawCalls={} multiDrawCalls={} multiDrawRanges={} multiDrawSaved={} "
                            + "| cpu group={} merge={} ranges={} capture={} direct={} restore={} ms/view | gpuOpaqueRange={} gpuTranslucentRange={} "
                            + "| window preparePoolGrowth commands={} prepared={} viewPoses={}",
                    slot == 0 ? "main" : "shadow", average(v[SCOPES], v[0]), average(v[GROUPS], v[0]),
                    average(v[SETUPS], v[0]), average(v[APPLIES], v[0]), average(v[MATRICES], v[0]), average(v[BINDS], v[0]),
                    average(v[BIND_REQUESTS], v[0]), average(v[BIND_REQUESTS] - v[BINDS], v[0]),
                    average(v[POSE_SNAPSHOTS], v[0]), average(v[POSE_VALUES], v[0]), average(v[POSE_RUNS], v[0]),
                    average(v[POSE_GROUPS], v[0]), average(v[LIGHT_GROUPS], v[0]), average(v[POSE_RUNS] - v[POSE_GROUPS], v[0]),
                    average(v[RANGE_BEFORE], v[0]), average(v[RANGE_AFTER], v[0]), average(v[RANGE_BEFORE] - v[RANGE_AFTER], v[0]),
                    average(v[OPAQUE_CALLS], v[0]), average(v[MULTI_CALLS], v[0]), average(v[MULTI_RANGES], v[0]),
                    average(v[RANGE_AFTER] - v[OPAQUE_CALLS], v[0]),
                    milliseconds(v[GROUP_CPU], v[0]), milliseconds(v[MERGE_CPU], v[0]), milliseconds(v[RANGE_CPU], v[0]), milliseconds(v[CAPTURE_CPU], v[0]),
                    milliseconds(v[DIRECT_CPU], v[0]), milliseconds(v[RESTORE_CPU], v[0]),
                    gpu(slot), slot == 0 ? gpu(2) : "notApplicable",
                    v[COMMAND_GROWTH], v[PREPARED_GROWTH], v[VIEW_POSE_GROWTH]);
            if (ranges[slot] != null) ranges[slot].log(slot);
        }
    }

    /** One warmed-up train submission per view/window; layout counts are not actual merged GPU calls. */
    static final class RangeSample {
        private final ResourceLocation model;
        private final long frame = IrisGeometryCache.frame();
        int requests, stateGroups, multiGroups, multiRequests, maxGroup, bufferGroups, sourceGroups,
                vertexSets, contiguousRuns, adjacentJoins, gapBreaks, overlapBreaks, duplicateRanges;
        long triangles, nanos;

        RangeSample(ResourceLocation model) { this.model = model; }
        void log(int slot) {
            LOGGER.info("Vehicle Iris ranges [{}] model={} frame={} | sample requests={} triangles={} stateGroups={} "
                            + "multiGroups={} multiRequests={} maxGroup={} bufferGroups={} sourceGroups={} vertexSets={} "
                            + "contiguousRuns={} adjacentJoins={} gapBreaks={} overlapBreaks={} duplicateRanges={} | cpu sample={}ms",
                    slot == 0 ? "main" : "shadow", model, frame, requests, triangles, stateGroups,
                    multiGroups, multiRequests, maxGroup, bufferGroups, sourceGroups, vertexSets,
                    contiguousRuns, adjacentJoins, gapBreaks, overlapBreaks, duplicateRanges, nanos / 1e6);
        }
    }

    public static final class Sample implements AutoCloseable {
        private final Sample previous = current;
        private final Window window;
        private final int slot;
        private final boolean submission;
        private final long begun = System.nanoTime();
        private long prepare, sort, draw, triangles, gpuDraws, cpuVertices, hits, misses, uploads;
        private long lightTriangles, lightGpuDraws, lightCpuVertices, lightHits, lightMisses, lightUploads;
        private long scopes, stateSetups, shaderApplies, matrixChanges, bufferBinds, bufferBindRequests, groups,
                groupNanos, captureNanos, directNanos, restoreNanos;
        private long poseSnapshots, poseValues, poseRuns, poseGroups, lightGroups;
        private long rangeNanos;
        private long rangeBefore, rangeAfter, mergeNanos;
        private long commandGrowth, preparedGrowth, viewPoseGrowth;
        private long opaqueCalls, multiCalls, multiRanges;
        private Sample(Window window, boolean shadow, boolean submission) {
            this.window = window;
            slot = shadow ? 1 : 0;
            this.submission = submission;
            current = this;
        }
        @Override public void close() {
            current = previous;
            long[] v = window.values[slot];
            if (window.viewFrame[slot] != IrisGeometryCache.frame()) {
                window.viewFrame[slot] = IrisGeometryCache.frame();
                v[0]++;
            }
            if (submission) v[1]++;
            v[2] += triangles; v[3] += gpuDraws; v[4] += cpuVertices;
            v[5] += hits; v[6] += misses; v[7] += prepare; v[8] += sort;
            v[9] += Math.max(0, System.nanoTime() - begun - prepare - sort); v[10] += uploads;
            v[11] += lightTriangles; v[12] += lightGpuDraws; v[13] += lightCpuVertices;
            v[14] += lightHits; v[15] += lightMisses; v[16] += lightUploads;
            v[SCOPES] += scopes; v[SETUPS] += stateSetups; v[APPLIES] += shaderApplies;
            v[MATRICES] += matrixChanges; v[BINDS] += bufferBinds; v[GROUPS] += groups;
            v[BIND_REQUESTS] += bufferBindRequests;
            v[GROUP_CPU] += groupNanos; v[CAPTURE_CPU] += captureNanos;
            v[DIRECT_CPU] += directNanos; v[RESTORE_CPU] += restoreNanos;
            v[POSE_SNAPSHOTS] += poseSnapshots; v[POSE_VALUES] += poseValues; v[POSE_RUNS] += poseRuns;
            v[POSE_GROUPS] += poseGroups; v[LIGHT_GROUPS] += lightGroups;
            v[RANGE_CPU] += rangeNanos;
            v[RANGE_BEFORE] += rangeBefore; v[RANGE_AFTER] += rangeAfter; v[MERGE_CPU] += mergeNanos;
            v[COMMAND_GROWTH] += commandGrowth; v[PREPARED_GROWTH] += preparedGrowth; v[VIEW_POSE_GROWTH] += viewPoseGrowth;
            v[OPAQUE_CALLS] += opaqueCalls; v[MULTI_CALLS] += multiCalls; v[MULTI_RANGES] += multiRanges;
            if (System.nanoTime() - window.start < 5_000_000_000L) return;
            var main = window.values[0]; var shadow = window.values[1];
            LOGGER.info(
                        "{} rendering [Iris cached]: window={}ms main views={} submissions={} triangles={} directGpuDraws={} cpuVertexRefs={} cacheHit/Miss={}/{} cpuPrepare/Sort/Submit={}/{}/{}ms ownedUpload={}B "
                            + "lights=triangles:{}/gpu:{}/cpuVertexRefs:{}/cache:{}/{} upload:{}B; "
                            + "shadow views={} submissions={} triangles={} directGpuDraws={} cpuVertexRefs={} cacheHit/Miss={}/{} cpuPrepare/Sort/Submit={}/{}/{}ms ownedUpload={}B "
                            + "lights=triangles:{}/gpu:{}/cpuVertexRefs:{}/cache:{}/{} upload:{}B; cache={}B peak={}B pending={}",
                    window.name, (System.nanoTime() - window.start) / 1e6, main[0], main[1], main[2], main[3], main[4], main[5], main[6],
                    main[7] / 1e6, main[8] / 1e6, main[9] / 1e6, main[10],
                    main[11], main[12], main[13], main[14], main[15], main[16],
                    shadow[0], shadow[1], shadow[2], shadow[3], shadow[4], shadow[5], shadow[6],
                    shadow[7] / 1e6, shadow[8] / 1e6, shadow[9] / 1e6, shadow[10],
                    shadow[11], shadow[12], shadow[13], shadow[14], shadow[15], shadow[16],
                    IrisGeometryCache.bytes(), IrisGeometryCache.peakBytes(), IrisGeometryCache.pending());
            if (window == WINDOWS[VEHICLE]) {
                window.logState(0);
                window.logState(1);
            }
            window.reset();
        }
    }

    /** Aggregate direct state counters without a clock read for every draw or GL state change. */
    static final class Scope {
        private final Sample sample;
        private final long begun = System.nanoTime();
        private long directStart, restoreStart;
        private IrisGpuTimer timer;
        int setups, applies, matrixChanges, bufferBinds, bufferBindRequests;

        Scope(Sample sample) { this.sample = sample; }
        void captured() {
            directStart = System.nanoTime();
            sample.captureNanos += directStart - begun;
            timer = sample.window.timer(sample.submission ? sample.slot : 2);
            timer.begin(sample.window.id);
        }
        void finishDraw() {
            restoreStart = System.nanoTime();
            sample.directNanos += restoreStart - directStart;
        }
        void restored(boolean completed) {
            timer.end(completed && bufferBinds != 0);
            sample.scopes++;
            sample.stateSetups += setups;
            sample.shaderApplies += applies;
            sample.matrixChanges += matrixChanges;
            sample.bufferBinds += bufferBinds;
            sample.bufferBindRequests += bufferBindRequests;
            sample.restoreNanos += System.nanoTime() - restoreStart;
        }
    }

    private IrisRenderStats() {}
    public static Sample begin(int kind, boolean shadow) {
        boolean enabled = kind == VEHICLE ? ClientConfig.LOG_VEHICLE_INSTANCING_STATS.getAsBoolean()
                : ClientConfig.LOG_TRACK_RENDER_STATS.getAsBoolean();
        if (!enabled) { WINDOWS[kind].release(); return null; }
        return new Sample(WINDOWS[kind], shadow, true);
    }
    static Sample beginTranslucentDraw() {
        return ClientConfig.LOG_VEHICLE_INSTANCING_STATS.getAsBoolean()
                ? new Sample(WINDOWS[VEHICLE], false, false) : null;
    }
    static Scope beginScope() {
        return current != null && current.window == WINDOWS[VEHICLE] ? new Scope(current) : null;
    }
    static void opaqueGrouping(long start, int groups, int snapshots, int poses, int runs, int poseGroups, int lightGroups) {
        if (current == null) return;
        current.groups += groups;
        current.poseSnapshots += snapshots;
        current.poseValues += poses;
        current.poseRuns += runs;
        current.poseGroups += poseGroups;
        current.lightGroups += lightGroups;
        current.groupNanos += System.nanoTime() - start;
    }
    static RangeSample rangeSample(TrainEntity train) {
        if (current == null || train == null) return null;
        var window = current.window;
        int slot = current.slot;
        if (window != WINDOWS[VEHICLE] || window.values[slot][0] < 16
                || current.misses != 0 || window.ranges[slot] != null) return null;
        var sample = new RangeSample(train.getModelId());
        window.ranges[slot] = sample;
        return sample;
    }
    static void rangeDiagnostics(long nanos) { if (current != null) current.rangeNanos += nanos; }
    static void opaqueMerge(int before, int after, long start) {
        if (current == null) return;
        current.rangeBefore += before;
        current.rangeAfter += after;
        current.mergeNanos += System.nanoTime() - start;
    }
    public static void beginFrame() {
        var window = WINDOWS[VEHICLE];
        if (!ClientConfig.LOG_VEHICLE_INSTANCING_STATS.getAsBoolean()) { window.release(); return; }
        for (var timer : window.timers) if (timer != null) timer.poll(window.id);
    }
    private static String average(long count, long views) { return String.format(Locale.ROOT, "%.1f", (double) count / views); }
    private static String milliseconds(long nanos, long views) { return String.format(Locale.ROOT, "%.3f", nanos / (views * 1e6)); }
    static boolean collecting() { return current != null; }
    static void commandPoolGrew() { if (current != null) current.commandGrowth++; }
    static void preparedPoolGrew() { if (current != null) current.preparedGrowth++; }
    static void viewPosePoolGrew() { if (current != null) current.viewPoseGrowth++; }
    public static long clock() { return current == null ? 0 : System.nanoTime(); }
    public static void prepare(long start) { if (current != null) current.prepare += Math.max(0, System.nanoTime() - start - current.draw); }
    public static void sort(long start) { if (current != null) current.sort += System.nanoTime() - start; }
    public static void submit(long start) { if (current != null) current.draw += System.nanoTime() - start; }
    public static void cache(boolean hit) { cache(hit, false); }
    public static void cache(boolean hit, boolean light) {
        if (current != null) {
            if (hit) current.hits++; else current.misses++;
            if (light) { if (hit) current.lightHits++; else current.lightMisses++; }
        }
    }
    public static void gpu(int triangles) { gpu(1, triangles); }
    public static void gpu(int calls, int triangles) { if (current != null) { current.gpuDraws += calls; current.triangles += triangles; } }
    static void opaqueDraw(int ranges, long triangles) {
        if (current == null) return;
        current.gpuDraws++;
        current.triangles += triangles;
        current.opaqueCalls++;
        if (ranges > 1) { current.multiCalls++; current.multiRanges += ranges; }
    }
    public static void lightGpu(int triangles) {
        if (current != null) { current.gpuDraws++; current.triangles += triangles; current.lightGpuDraws++; current.lightTriangles += triangles; }
    }
    public static void buffered(int triangles) { if (current != null) { current.triangles += triangles; current.cpuVertices += (long)triangles * 3; } }
    public static void lightBuffered(int triangles) {
        if (current != null) {
            current.triangles += triangles; current.cpuVertices += (long)triangles * 3;
            current.lightTriangles += triangles; current.lightCpuVertices += (long)triangles * 3;
        }
    }
    public static void owned(int triangles) { if (current != null) { current.gpuDraws++; current.triangles += triangles; current.cpuVertices += (long)triangles * 3; } }
    public static void upload(long bytes) { upload(bytes, false); }
    public static void upload(long bytes, boolean light) {
        if (current != null) { current.uploads += bytes; if (light) current.lightUploads += bytes; }
    }
    public static void reset() { for (var window : WINDOWS) window.release(); }
}
