/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import com.mojang.blaze3d.systems.RenderSystem;
import com.unrealtrainmod.client.ClientConfig;
import com.unrealtrainmod.client.compat.iris.IrisDrawScope;
import java.util.HashSet;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-frame track counters, CPU timings and sampled GPU spans, reported in roughly five-second windows.
 *
 * <p>Counts are taken where the work happens. Draw calls are counted per actual GL submission, split into
 * four separate buckets by geometry source (native quads or an authored rail model) and structure
 * (ordinary segment or assembly/turnout); each draw belongs to exactly one bucket. CPU submission
 * costs and asynchronous GPU timestamp spans are reported separately.
 */
final class TrackRenderStats {
    private static final Logger LOGGER = LoggerFactory.getLogger("UnrealTrainMod/Track");
    private static final long WINDOW_NANOS = 5_000_000_000L;
    /** Frames stop arriving when statistics are switched off or the scene changes; drop that stale window. */
    private static final long IDLE_RESET_NANOS = 1_000_000_000L;
    private static final TrackRenderStats INSTANCE = new TrackRenderStats();
    private static final TrackRenderStats SHADOW = new TrackRenderStats();

    /** Geometry source: native quads or an authored rail model. */
    static final int NATIVE = 0;
    static final int RAIL_MODEL = 1;
    /** Structure: ordinary track segments or assembly/turnout groups. */
    static final int STRUCTURE_TRACK = 0;
    static final int STRUCTURE_ASSEMBLY = 1;
    private static final int SLOTS = 4;
    private record ChunkMaterial(long chunk, RenderType type) {}

    private ClientLevel level;
    private String scene = "";
    private String path = "";
    private long windowStart = System.nanoTime();
    private long lastFrame = windowStart;
    private int windowFrames;
    private long windowId;
    private TrackGpuTimer gpuTimer;
    private long windowGpu, windowMaxGpu;
    private int windowGpuSamples;
    private long windowAnimatedGpu, windowMaxAnimatedGpu;
    private int windowAnimatedGpuSamples;
    private long windowAnimatedTurnouts, windowJsCalls, windowJsNanos, windowDynamicParts;
    private long windowInstanceBytes, windowActiveBytes;
    private int animatedTurnouts, dynamicParts;
    private long jsCalls, jsNanos, instanceBytes, activeBytes;
    private long windowStage, windowMaxStage, windowBuild, windowLight, windowPack, windowVbo, windowGroup, windowDraw;
    private long windowChunks, windowCandidates, windowVisible, windowEmpty, windowPending;
    private final long[] windowDraws = new long[SLOTS];
    private final long[] windowVertices = new long[SLOTS];
    private final long[] windowTriangles = new long[SLOTS];
    private long windowBuilds, windowUploads, windowFirstUploads, windowRefreshUploads;
    private long windowVboBytes, windowIboBytes, windowStagingBytes, windowLightChecked, windowLightChanged;
    private long windowStoredVertices, windowRailChunkBatches, windowRailChunkMaterials;
    private long windowRenderTypeSetups, windowShaderBinds, windowProgramSwitches;
    private long windowBudgetSkipped, windowSlowBuilds, windowMaxSlowBuild;
    private String windowSlowResource = "n/a";

    private long windowRangeBefore, windowRangeAfter, windowMultiDrawCalls, windowMultiDrawRanges;
    private long windowLightBytes, windowPageBuilds, windowPageRebuilds, windowResidentPages, windowResidentBytes;
    private int rangeBefore, rangeAfter, multiDrawCalls, multiDrawRanges, pageBuilds, pageRebuilds, residentPages;
    private long lightBytes, residentBytes;

    private int chunks, candidates, visible, empty, pending;
    private final int[] draws = new int[SLOTS];
    private final int[] vertices = new int[SLOTS];
    private final int[] triangles = new int[SLOTS];
    private int builds, uploads, firstUploads, refreshUploads;
    private long vboBytes, iboBytes, stagingBytes;
    private int storedVertices, railChunkBatches;
    private final Set<ChunkMaterial> railChunkMaterials = new HashSet<>();
    private final Set<SharedRailPart> sharedParts = Collections.newSetFromMap(new IdentityHashMap<>());
    private long lightChecked, lightChanged;
    private int renderTypeSetups, shaderBinds, programSwitches;
    private int irisProgram = -1;
    private long stageNanos, buildNanos, lightNanos, packNanos, vboNanos, groupNanos, drawNanos;

    private TrackRenderStats() {}

    /** Returns the shared collector for this frame, or null when statistics are disabled. */
    static TrackRenderStats begin(ClientLevel level, String scene, String path) {
        if (!ClientConfig.LOG_TRACK_RENDER_STATS.getAsBoolean()) {
            INSTANCE.closeGpuTimer();
            SHADOW.closeGpuTimer();
            return null;
        }
        return (scene.equals("shadow") ? SHADOW : INSTANCE).start(level, scene, path);
    }

    private static int slot(int source, int structure) {
        return source * 2 + structure;
    }

    /** Drops the current window and its level reference when a world closes. */
    static void reset() {
        long now = System.nanoTime();
        INSTANCE.closeGpuTimer();
        INSTANCE.level = null;
        INSTANCE.lastFrame = now;
        INSTANCE.resetWindow(now);
        SHADOW.closeGpuTimer();
        SHADOW.level = null;
        SHADOW.lastFrame = now;
        SHADOW.resetWindow(now);
    }

    private TrackRenderStats start(ClientLevel level, String scene, String path) {
        long now = System.nanoTime();
        if (gpuTimer == null || this.level != level || !scene.equals(this.scene) || !path.equals(this.path)
                || now - lastFrame > IDLE_RESET_NANOS) {
            this.level = level;
            this.scene = scene;
            this.path = path;
            resetWindow(now);
        }
        resetFrame();
        if (gpuTimer == null) gpuTimer = new TrackGpuTimer();
        gpuTimer.beginFrame(windowId, this);
        return this;
    }

    private void closeGpuTimer() {
        var timer = gpuTimer;
        if (timer == null) return;
        gpuTimer = null;
        if (RenderSystem.isOnRenderThread()) timer.close();
        else RenderSystem.recordRenderCall(timer::close);
    }

    private void resetWindow(long now) {
        windowId++;
        windowGpu = windowMaxGpu = 0L;
        windowGpuSamples = 0;
        windowAnimatedGpu = windowMaxAnimatedGpu = 0;
        windowAnimatedGpuSamples = 0;
        windowAnimatedTurnouts = windowJsCalls = windowJsNanos = windowDynamicParts = 0;
        windowInstanceBytes = windowActiveBytes = 0;
        windowStart = now;
        windowFrames = 0;
        windowStage = windowMaxStage = windowBuild = windowLight = windowPack = windowVbo = windowGroup = windowDraw = 0L;
        windowChunks = windowCandidates = windowVisible = windowEmpty = windowPending = 0L;
        for (int i = 0; i < SLOTS; i++) {
            windowDraws[i] = windowVertices[i] = windowTriangles[i] = 0L;
        }
        windowBuilds = windowUploads = windowFirstUploads = windowRefreshUploads = 0L;
        windowVboBytes = windowIboBytes = windowStagingBytes = windowLightChecked = windowLightChanged = 0L;
        windowStoredVertices = windowRailChunkBatches = windowRailChunkMaterials = 0L;
        railChunkMaterials.clear();
        sharedParts.clear();
        windowRenderTypeSetups = windowShaderBinds = windowProgramSwitches = 0L;
        windowBudgetSkipped = windowSlowBuilds = windowMaxSlowBuild = 0L;
        windowSlowResource = "n/a";
        windowRangeBefore = windowRangeAfter = windowMultiDrawCalls = windowMultiDrawRanges = 0;
        windowLightBytes = windowPageBuilds = windowPageRebuilds = windowResidentPages = windowResidentBytes = 0;
    }

    private void resetFrame() {
        irisProgram = -1;
        rangeBefore = rangeAfter = multiDrawCalls = multiDrawRanges = pageBuilds = pageRebuilds = residentPages = 0;
        lightBytes = residentBytes = 0;
        animatedTurnouts = dynamicParts = 0;
        jsCalls = jsNanos = instanceBytes = activeBytes = 0;
        chunks = candidates = visible = empty = pending = 0;
        for (int i = 0; i < SLOTS; i++) {
            draws[i] = vertices[i] = triangles[i] = 0;
        }
        builds = uploads = firstUploads = refreshUploads = 0;
        vboBytes = iboBytes = stagingBytes = 0L;
        storedVertices = railChunkBatches = 0;
        railChunkMaterials.clear();
        sharedParts.clear();
        lightChecked = lightChanged = 0L;
        renderTypeSetups = shaderBinds = programSwitches = 0;
        stageNanos = buildNanos = lightNanos = packNanos = vboNanos = groupNanos = drawNanos = 0L;
    }

    /** One visited chunk entry; the plain-segment and assembly loops both count their visits. */
    void chunk() { chunks++; }
    void candidate() { candidates++; }
    void visible() { visible++; }
    void empty() { empty++; }
    void pending() { pending++; }

    void animation(int count, long calls, long nanos) {
        animatedTurnouts = count; jsCalls += calls; jsNanos += nanos;
    }
    void dynamicParts(int count) { dynamicParts += count; }
    void instanceUpload(long bytes) { instanceBytes += bytes; }
    void activeUpload(long bytes) { activeBytes += bytes; }
    int sharedPartVertices(SharedRailPart part) { return sharedParts.add(part) ? part.source.colors().length : 0; }

    void built(long nanos) {
        builds++;
        buildNanos += nanos;
    }

    /** A visible missing task deferred by the time or task-count budget. */
    void budgetSkipped() { windowBudgetSkipped++; }

    void slowBuild(long nanos, String resource) {
        windowSlowBuilds++;
        if (nanos > windowMaxSlowBuild) {
            windowMaxSlowBuild = nanos;
            windowSlowResource = resource;
        }
    }

    /** Static geometry uploads, including replacement pages; UV2 bytes are recorded separately. */
    void uploaded(int vertexBytes, int indexBytes, int stagingCapacity) {
        uploads++;
        vboBytes += vertexBytes;
        iboBytes += indexBytes;
        stagingBytes += stagingCapacity;
        firstUploads++;
    }

    void lightUploaded(int bytes, boolean initial, boolean animated) {
        lightBytes += bytes;
        stagingBytes += bytes;
        if (animated) activeBytes += bytes;
        else if (!initial) { uploads++; refreshUploads++; }
    }

    void sharedPageBuilt(boolean replacement) {
        builds++; pageBuilds++;
        if (replacement) pageRebuilds++;
    }

    void sharedPages(int count, long bytes) { residentPages = count; residentBytes = bytes; }

    void ranges(int before, int after, int multiRanges) {
        rangeBefore += before; rangeAfter += after;
        if (multiRanges > 0) { multiDrawCalls++; multiDrawRanges += multiRanges; }
    }

    void packed(long nanos) { packNanos += nanos; }

    void gpuUpload(long nanos) { vboNanos += nanos; }

    /** Matrix snapshots and insertion into the frame-local material groups, excluding actual draws. */
    void grouped(long nanos) { groupNanos += nanos; }

    /** Complete grouped submission; its individual draw counts are recorded with zero per-draw time. */
    void submitted(long nanos) { drawNanos += nanos; }

    void lightRefresh(long nanos, int checked, int changed) {
        lightNanos += nanos;
        lightChecked += checked;
        lightChanged += changed;
    }

    void renderTypeSetup() { renderTypeSetups++; }

    /** A shader {@code apply()} issued by the draw scope. */
    void irisShaderBind() {
        if (!IrisDrawScope.current().lastDrawAppliedShader()) return;
        int program = RenderSystem.getShader().getId();
        shaderBind(irisProgram != -1 && irisProgram != program);
        irisProgram = program;
    }

    void shaderBind(boolean programSwitch) {
        shaderBinds++;
        if (programSwitch) programSwitches++;
    }

    void drawn(int source, int structure, int drawCalls, int vertexCount, int triangleCount, int storedVertexCount, long nanos) {
        int index = slot(source, structure);
        draws[index] += drawCalls;
        vertices[index] += vertexCount;
        triangles[index] += triangleCount;
        storedVertices += storedVertexCount;
        drawNanos += nanos;
    }

    /** Actual ordinary authored-rail draws only; assemblies are not merge candidates. */
    void railChunkBatch(long chunk, RenderType type) {
        railChunkBatches++;
        railChunkMaterials.add(new ChunkMaterial(chunk, type));
    }

    /** Draw-scope cleanup after the last track mesh, counted into the draw submission stage. */
    void drawCleanup(long nanos) { drawNanos += nanos; }

    /** Whole track stage including state backup, failure flushing, traversal, build, upload, draw and restore. */
    void stage(long nanos) { stageNanos = nanos; }

    /** Separate stationary and animation samples; streaming uploads stay in animation spans. */
    void gpuSpan(long nanos, boolean animated) {
        if (animated) {
            windowAnimatedGpu += nanos;
            windowMaxAnimatedGpu = Math.max(windowMaxAnimatedGpu, nanos);
            windowAnimatedGpuSamples++;
            return;
        }
        windowGpu += nanos;
        windowMaxGpu = Math.max(windowMaxGpu, nanos);
        windowGpuSamples++;
    }

    void endFrame() {
        int drawCount = 0;
        for (int value : draws) drawCount += value;
        gpuTimer.endFrame(drawCount > 0 && builds == 0 && uploads == 0,
                animatedTurnouts > 0 || instanceBytes > 0 || activeBytes > 0);
        windowAnimatedTurnouts += animatedTurnouts;
        windowJsCalls += jsCalls; windowJsNanos += jsNanos; windowDynamicParts += dynamicParts;
        windowInstanceBytes += instanceBytes; windowActiveBytes += activeBytes;
        windowFrames++;
        windowStage += stageNanos;
        if (stageNanos > windowMaxStage) windowMaxStage = stageNanos;
        windowDraw += drawNanos;
        windowGroup += groupNanos;
        windowBuild += buildNanos;
        windowLight += lightNanos;
        windowPack += packNanos;
        windowVbo += vboNanos;
        windowChunks += chunks;
        windowCandidates += candidates;
        windowVisible += visible;
        windowEmpty += empty;
        windowPending += pending;
        for (int i = 0; i < SLOTS; i++) {
            windowDraws[i] += draws[i];
            windowVertices[i] += vertices[i];
            windowTriangles[i] += triangles[i];
        }
        windowRangeBefore += rangeBefore; windowRangeAfter += rangeAfter;
        windowMultiDrawCalls += multiDrawCalls; windowMultiDrawRanges += multiDrawRanges;
        windowLightBytes += lightBytes;
        windowPageBuilds += pageBuilds; windowPageRebuilds += pageRebuilds;
        windowResidentPages += residentPages; windowResidentBytes += residentBytes;
        windowBuilds += builds;
        windowUploads += uploads;
        windowFirstUploads += firstUploads;
        windowRefreshUploads += refreshUploads;
        windowVboBytes += vboBytes;
        windowIboBytes += iboBytes;
        windowStoredVertices += storedVertices;
        windowRailChunkBatches += railChunkBatches;
        windowRailChunkMaterials += railChunkMaterials.size();
        windowStagingBytes += stagingBytes;
        windowLightChecked += lightChecked;
        windowLightChanged += lightChanged;
        windowRenderTypeSetups += renderTypeSetups;
        windowShaderBinds += shaderBinds;
        windowProgramSwitches += programSwitches;
        stageNanos = 0L;
        lastFrame = System.nanoTime();
    }

    void logIfDue() {
        long now = System.nanoTime();
        if (windowFrames == 0 || now - windowStart < WINDOW_NANOS) return;
        int frames = windowFrames;
        long drawSlots = 0L, vertexSlots = 0L, triangleSlots = 0L;
        for (int i = 0; i < SLOTS; i++) {
            drawSlots += windowDraws[i];
            vertexSlots += windowVertices[i];
            triangleSlots += windowTriangles[i];
        }
        // Traversal and culling are the remainder: no nested timing is added back into the stage total.
        // It also contains staging-buffer allocation, upload VAO binding and draw-scope state backup.
        // Draw-scope cleanup is already included in windowDraw.
        long traverse = Math.max(0L, windowStage - windowBuild - windowLight - windowPack - windowVbo - windowGroup - windowDraw);
        LOGGER.info("Track [{}] path={} bufferMode=sharedPages frames={} window={}ms stage avg={}ms max={}ms "
                        + "(traverse={} build={} light={} pack={} vbo={} group={} draw={} ms/frame) "
                        + "| perFrame chunks={} candidates={} visible={} empty={} pending={} "
                        + "draws={} [nativeTrack={} railTrack={} nativeAssembly={} railAssembly={}] "
                        + "verts={} tris={} storedVerts={} builds={} uploads={} (first={} refresh={}) "
                        + "vboBytes={} iboBytes={} stagingBytes={} rtSetups={} shaderBinds={} programSwitches={} "
                        + "lightChecked={} lightChanged={} "
                        + "| window draws={} verts={} tris={} builds={} uploads={} vboBytes={} iboBytes={} "
                        + "| chunkMerge=counted railBatches={} chunkMaterials={} extraBatches={} "
                        + "| gpuStill={} samples={} avg={}ms max={}ms "
                        + "| animation turnouts={} jsCalls={} js={}ms parts={} instanceBytes={} activeBytes={} staticBytes={} "
                        + "gpuMoving samples={} avg={}ms max={}ms "
                        + "| buildBudget skipped={} slowBuilds={} maxSlow={}ms resource={}",
                scene, path, frames, ms(now - windowStart),
                ms(windowStage / frames), ms(windowMaxStage),
                ms(traverse / frames), ms(windowBuild / frames), ms(windowLight / frames),
                ms(windowPack / frames), ms(windowVbo / frames), ms(windowGroup / frames), ms(windowDraw / frames),
                avg(windowChunks, frames), avg(windowCandidates, frames), avg(windowVisible, frames),
                avg(windowEmpty, frames), avg(windowPending, frames),
                avg(drawSlots, frames), avg(windowDraws[slot(NATIVE, STRUCTURE_TRACK)], frames),
                avg(windowDraws[slot(RAIL_MODEL, STRUCTURE_TRACK)], frames),
                avg(windowDraws[slot(NATIVE, STRUCTURE_ASSEMBLY)], frames),
                avg(windowDraws[slot(RAIL_MODEL, STRUCTURE_ASSEMBLY)], frames),
                avg(vertexSlots, frames), avg(triangleSlots, frames), avg(windowStoredVertices, frames),
                avg(windowBuilds, frames), avg(windowUploads, frames),
                avg(windowFirstUploads, frames), avg(windowRefreshUploads, frames),
                windowVboBytes / frames, windowIboBytes / frames, windowStagingBytes / frames,
                avg(windowRenderTypeSetups, frames), avg(windowShaderBinds, frames),
                avg(windowProgramSwitches, frames), avg(windowLightChecked, frames),
                avg(windowLightChanged, frames),
                drawSlots, vertexSlots, triangleSlots, windowBuilds, windowUploads, windowVboBytes, windowIboBytes,
                avg(windowRailChunkBatches, frames), avg(windowRailChunkMaterials, frames),
                avg(windowRailChunkBatches - windowRailChunkMaterials, frames),
                !gpuTimer.supported() ? "unsupported" : windowGpuSamples == 0 ? "noSamples" : "sampled",
                windowGpuSamples, windowGpuSamples == 0 ? "n/a" : ms(windowGpu / windowGpuSamples),
                windowGpuSamples == 0 ? "n/a" : ms(windowMaxGpu),
                avg(windowAnimatedTurnouts, frames), windowJsCalls, ms(windowJsNanos), avg(windowDynamicParts, frames),
                windowInstanceBytes, windowActiveBytes, windowVboBytes + windowIboBytes,
                windowAnimatedGpuSamples,
                windowAnimatedGpuSamples == 0 ? "n/a" : ms(windowAnimatedGpu / windowAnimatedGpuSamples),
                windowAnimatedGpuSamples == 0 ? "n/a" : ms(windowMaxAnimatedGpu),
                windowBudgetSkipped, windowSlowBuilds, ms(windowMaxSlowBuild), windowSlowResource);
        LOGGER.info("Track buffers [{}] path={} bufferMode=sharedPages perFrame rangesBefore={} rangesAfter={} multiDrawCalls={} "
                        + "multiDrawRanges={} savedCalls={} lightBytes={} pageBuilds={} pageRebuilds={} residentPages={} residentBytes={}",
                scene, path, avg(windowRangeBefore, frames), avg(windowRangeAfter, frames),
                avg(windowMultiDrawCalls, frames), avg(windowMultiDrawRanges, frames),
                avg(windowRangeBefore - windowRangeAfter + windowMultiDrawRanges - windowMultiDrawCalls, frames),
                windowLightBytes / frames, avg(windowPageBuilds, frames), avg(windowPageRebuilds, frames),
                avg(windowResidentPages, frames), windowResidentBytes / frames);
        resetWindow(now);
    }

    private static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0);
    }

    private static String avg(long total, int frames) {
        return String.format(Locale.ROOT, "%.1f", total / (double)frames);
    }
}
