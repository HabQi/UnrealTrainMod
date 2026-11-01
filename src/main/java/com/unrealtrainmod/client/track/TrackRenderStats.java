/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.client.ClientConfig;
import java.util.Locale;
import net.minecraft.client.multiplayer.ClientLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-frame track counters and CPU stage timings, reported in roughly five-second windows.
 *
 * <p>Counts are taken where the work happens. Draw calls are counted per actual GL submission, split into
 * four separate buckets by geometry source (native quads or an authored rail model) and structure
 * (ordinary segment or assembly/turnout); each draw belongs to exactly one bucket. Timings are CPU
 * submission costs only.
 */
final class TrackRenderStats {
    private static final Logger LOGGER = LoggerFactory.getLogger("UnrealTrainMod/Track");
    private static final long WINDOW_NANOS = 5_000_000_000L;
    /** Frames stop arriving when statistics are switched off or the scene changes; drop that stale window. */
    private static final long IDLE_RESET_NANOS = 1_000_000_000L;
    private static final TrackRenderStats INSTANCE = new TrackRenderStats();

    /** Geometry source: native quads or an authored rail model. */
    static final int NATIVE = 0;
    static final int RAIL_MODEL = 1;
    /** Structure: ordinary track segments or assembly/turnout groups. */
    static final int STRUCTURE_TRACK = 0;
    static final int STRUCTURE_ASSEMBLY = 1;
    private static final int SLOTS = 4;

    private ClientLevel level;
    private String scene = "";
    private String path = "";
    private long windowStart = System.nanoTime();
    private long lastFrame = windowStart;
    private int windowFrames;
    private long windowStage, windowMaxStage, windowBuild, windowLight, windowPack, windowVbo, windowDraw;
    private long windowChunks, windowCandidates, windowVisible, windowEmpty, windowPending;
    private final long[] windowDraws = new long[SLOTS];
    private final long[] windowVertices = new long[SLOTS];
    private final long[] windowTriangles = new long[SLOTS];
    private long windowBuilds, windowUploads, windowFirstUploads, windowRefreshUploads;
    private long windowVboBytes, windowStagingBytes, windowLightChecked, windowLightChanged;
    private long windowRenderTypeSetups, windowShaderBinds, windowProgramSwitches;

    private int chunks, candidates, visible, empty, pending;
    private final int[] draws = new int[SLOTS];
    private final int[] vertices = new int[SLOTS];
    private final int[] triangles = new int[SLOTS];
    private int builds, uploads, firstUploads, refreshUploads;
    private long vboBytes, stagingBytes;
    private long lightChecked, lightChanged;
    private int renderTypeSetups, shaderBinds, programSwitches;
    private long stageNanos, buildNanos, lightNanos, packNanos, vboNanos, drawNanos;

    private TrackRenderStats() {}

    /** Returns the shared collector for this frame, or null when statistics are disabled. */
    static TrackRenderStats begin(ClientLevel level, String scene, String path) {
        if (!ClientConfig.LOG_TRACK_RENDER_STATS.getAsBoolean()) return null;
        return INSTANCE.start(level, scene, path);
    }

    private static int slot(int source, int structure) {
        return source * 2 + structure;
    }

    /** Drops the current window and its level reference when a world closes. */
    static void reset() {
        long now = System.nanoTime();
        INSTANCE.level = null;
        INSTANCE.lastFrame = now;
        INSTANCE.resetWindow(now);
    }

    private TrackRenderStats start(ClientLevel level, String scene, String path) {
        long now = System.nanoTime();
        if (this.level != level || !scene.equals(this.scene) || !path.equals(this.path)
                || now - lastFrame > IDLE_RESET_NANOS) {
            this.level = level;
            this.scene = scene;
            this.path = path;
            resetWindow(now);
        }
        resetFrame();
        return this;
    }

    private void resetWindow(long now) {
        windowStart = now;
        windowFrames = 0;
        windowStage = windowMaxStage = windowBuild = windowLight = windowPack = windowVbo = windowDraw = 0L;
        windowChunks = windowCandidates = windowVisible = windowEmpty = windowPending = 0L;
        for (int i = 0; i < SLOTS; i++) {
            windowDraws[i] = windowVertices[i] = windowTriangles[i] = 0L;
        }
        windowBuilds = windowUploads = windowFirstUploads = windowRefreshUploads = 0L;
        windowVboBytes = windowStagingBytes = windowLightChecked = windowLightChanged = 0L;
        windowRenderTypeSetups = windowShaderBinds = windowProgramSwitches = 0L;
    }

    private void resetFrame() {
        chunks = candidates = visible = empty = pending = 0;
        for (int i = 0; i < SLOTS; i++) {
            draws[i] = vertices[i] = triangles[i] = 0;
        }
        builds = uploads = firstUploads = refreshUploads = 0;
        vboBytes = stagingBytes = 0L;
        lightChecked = lightChanged = 0L;
        renderTypeSetups = shaderBinds = programSwitches = 0;
        stageNanos = buildNanos = lightNanos = packNanos = vboNanos = drawNanos = 0L;
    }

    /** One visited chunk entry; the plain-segment and assembly loops both count their visits. */
    void chunk() { chunks++; }
    void candidate() { candidates++; }
    void visible() { visible++; }
    void empty() { empty++; }
    void pending() { pending++; }

    void built(long nanos) {
        builds++;
        buildNanos += nanos;
    }

    /** One successful VBO upload; non-first uploads are light refreshes because geometry is static. */
    void uploaded(int byteCount, int stagingCapacity, boolean first) {
        uploads++;
        vboBytes += byteCount;
        stagingBytes += stagingCapacity;
        if (first) firstUploads++;
        else refreshUploads++;
    }

    void packed(long nanos) { packNanos += nanos; }

    void gpuUpload(long nanos) { vboNanos += nanos; }

    void lightRefresh(long nanos, int checked, int changed) {
        lightNanos += nanos;
        lightChecked += checked;
        lightChanged += changed;
    }

    void renderTypeSetup() { renderTypeSetups++; }

    /** A shader {@code apply()} issued by the draw scope. */
    void shaderBind(boolean programSwitch) {
        shaderBinds++;
        if (programSwitch) programSwitches++;
    }

    /**
     * Original path: every draw applies the shader and clears it again, so the following apply rebinds
     * the program. The first bind of the frame is not counted as a switch, matching the scope path.
     */
    void legacyShaderBind() {
        shaderBinds++;
        if (shaderBinds > 1) programSwitches++;
    }

    void drawn(int source, int structure, int drawCalls, int vertexCount, int triangleCount, long nanos) {
        int index = slot(source, structure);
        draws[index] += drawCalls;
        vertices[index] += vertexCount;
        triangles[index] += triangleCount;
        drawNanos += nanos;
    }

    /** Draw-scope cleanup after the last track mesh, counted into the draw submission stage. */
    void drawCleanup(long nanos) { drawNanos += nanos; }

    /** Whole track stage including state backup, failure flushing, traversal, build, upload, draw and restore. */
    void stage(long nanos) { stageNanos = nanos; }

    void endFrame() {
        windowFrames++;
        windowStage += stageNanos;
        if (stageNanos > windowMaxStage) windowMaxStage = stageNanos;
        windowDraw += drawNanos;
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
        windowBuilds += builds;
        windowUploads += uploads;
        windowFirstUploads += firstUploads;
        windowRefreshUploads += refreshUploads;
        windowVboBytes += vboBytes;
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
        long traverse = Math.max(0L, windowStage - windowBuild - windowLight - windowPack - windowVbo - windowDraw);
        LOGGER.info("Track [{}] path={} frames={} stage avg={}ms max={}ms "
                        + "(traverse={} build={} light={} pack={} vbo={} draw={} ms/frame) "
                        + "| perFrame chunks={} candidates={} visible={} empty={} pending={} "
                        + "draws={} [nativeTrack={} railTrack={} nativeAssembly={} railAssembly={}] "
                        + "verts={} tris={} builds={} uploads={} (first={} refresh={}) "
                        + "vboBytes={} stagingBytes={} rtSetups={} shaderBinds={} programSwitches={} "
                        + "lightChecked={} lightChanged={} "
                        + "| window draws={} verts={} tris={} builds={} uploads={} vboBytes={}",
                scene, path, frames,
                ms(windowStage / frames), ms(windowMaxStage),
                ms(traverse / frames), ms(windowBuild / frames), ms(windowLight / frames),
                ms(windowPack / frames), ms(windowVbo / frames), ms(windowDraw / frames),
                avg(windowChunks, frames), avg(windowCandidates, frames), avg(windowVisible, frames),
                avg(windowEmpty, frames), avg(windowPending, frames),
                avg(drawSlots, frames), avg(windowDraws[slot(NATIVE, STRUCTURE_TRACK)], frames),
                avg(windowDraws[slot(RAIL_MODEL, STRUCTURE_TRACK)], frames),
                avg(windowDraws[slot(NATIVE, STRUCTURE_ASSEMBLY)], frames),
                avg(windowDraws[slot(RAIL_MODEL, STRUCTURE_ASSEMBLY)], frames),
                avg(vertexSlots, frames), avg(triangleSlots, frames),
                avg(windowBuilds, frames), avg(windowUploads, frames),
                avg(windowFirstUploads, frames), avg(windowRefreshUploads, frames),
                windowVboBytes / frames, windowStagingBytes / frames,
                avg(windowRenderTypeSetups, frames), avg(windowShaderBinds, frames),
                avg(windowProgramSwitches, frames), avg(windowLightChecked, frames),
                avg(windowLightChanged, frames),
                drawSlots, vertexSlots, triangleSlots, windowBuilds, windowUploads, windowVboBytes);
        resetWindow(now);
    }

    private static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0);
    }

    private static String avg(long total, int frames) {
        return String.format(Locale.ROOT, "%.1f", total / (double)frames);
    }
}
