/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm.script;

import com.unrealtrainmod.resource.PackageAsset;
import com.unrealtrainmod.client.ClientConfig;
import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.script.ScriptException;
import com.unrealtrainmod.track.geometry.TrackGeometry;
import com.unrealtrainmod.track.assembly.TrackAssemblyInstance;
import com.unrealtrainmod.track.mesh.RailTrackMeshBuilder;
import com.unrealtrainmod.track.mesh.RailTrackMeshData;
import java.io.IOException;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Isolated legacy rail script generating cached normal-track or custom-turnout geometry. */
public final class RailScriptRuntime implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("UnrealTrainMod RTM scripts");

    private final RtmScriptInstance instance;
    private final RtmScriptProgram program;
    private final String railName;
    private final List<RailTrackMeshBuilder.Source> sources;
    private final RailScriptRenderer renderer;
    private final RtmScriptSource.Prepared prepared;
    private final RailScriptTile tile;
    private final boolean hasShouldRenderObject;
    private final boolean hasRenderRailStatic;
    private final boolean hasRenderRailDynamic;
    private final String label;
    private static long pendingCalls, pendingNanos;
    private int invocationDepth;
    private boolean worldDraw;

    public record Metrics(long calls, long nanos) {}
    public static Metrics drainMetrics() {
        var result = new Metrics(pendingCalls, pendingNanos);
        pendingCalls = pendingNanos = 0;
        return result;
    }

    private Object invoke(String name, Object... arguments) throws ScriptException, NoSuchMethodException {
        boolean measured = worldDraw && ClientConfig.LOG_TRACK_RENDER_STATS.getAsBoolean();
        boolean outer = invocationDepth++ == 0;
        long start = measured && outer ? System.nanoTime() : 0;
        if (measured) pendingCalls++;
        try { return instance.invoke(name, arguments); }
        finally {
            invocationDepth--;
            if (start != 0) pendingNanos += System.nanoTime() - start;
        }
    }

    private boolean failed;
    private String phase;
    private Throwable filterFailure;

    private RailScriptRuntime(RtmScriptInstance instance, RailScriptRenderer renderer,
                              RtmScriptProgram program, RailScriptTile tile,
                              boolean hasShouldRenderObject, boolean hasRenderRailStatic, boolean hasRenderRailDynamic,
                              String railName, String label, List<RailTrackMeshBuilder.Source> sources) {
        this.instance = instance;
        this.program = program;
        this.railName = railName;
        this.sources = List.copyOf(sources);
        this.renderer = renderer;
        this.prepared = program.prepared;
        this.tile = tile;
        this.hasShouldRenderObject = hasShouldRenderObject;
        this.hasRenderRailStatic = hasRenderRailStatic;
        this.hasRenderRailDynamic = hasRenderRailDynamic;
        this.label = label;
    }

    public static RailScriptRuntime create(RtmScriptCache cache, PackageAsset script, String railName, String label,
                                            List<RailTrackMeshBuilder.Source> sources)
            throws IOException {
        var runtime = create(cache.program(script), railName, label, sources, null);
        ScriptDiagnostics.scriptScope("rail", script, RtmScript.RAIL_RENDERER
                + (runtime.hasRenderRailStatic ? "; renderRailStatic builds cached geometry at Pass 0"
                        : "; no renderRailStatic, default static repetition is used")
                + (runtime.hasShouldRenderObject ? "; shouldRenderObject filters renderStaticParts" : "; no object filter")
                + (runtime.hasRenderRailDynamic ? "; renderRailDynamic captures tick snapshots for custom turnouts" : ""));
        return runtime;
    }

    /** Shares compiled code, never the tile, global variables, lists or failure state. */
    public RailScriptRuntime newAssembly(TrackAssemblyInstance assembly) throws IOException {
        return create(program, railName, label + " [turnout " + assembly.id() + "]", sources, assembly);
    }

    private static RailScriptRuntime create(RtmScriptProgram program, String railName, String label,
                                             List<RailTrackMeshBuilder.Source> sources, TrackAssemblyInstance assembly)
            throws IOException {
        var renderer = new RailScriptRenderer(sources, label);
        var tile = new RailScriptTile(railName);
        if (assembly != null) tile.assembly(assembly);
        RtmScriptInstance instance = null;
        boolean transferred = false;
        try {
            instance = new RtmScriptInstance(program);
            instance.put("renderer", renderer);
            instance.put("tileEntity", tile);
            instance.eval();
            String renderClass = instance.renderClass();
            if (!RtmScript.RAIL_RENDERER.equals(renderClass)) {
                throw new IOException("Script entry check failed: renderClass '" + renderClass
                        + "'; expected " + RtmScript.RAIL_RENDERER);
            }
            if (!instance.hasFunction("init")) throw new IOException("Script entry check failed: missing init(par1, par2)");
            instance.init();
            boolean hasShouldRenderObject = instance.hasFunction("shouldRenderObject");
            boolean hasRenderRailStatic = instance.hasFunction("renderRailStatic");
            boolean hasRenderRailDynamic = instance.hasFunction("renderRailDynamic");
            var runtime = new RailScriptRuntime(instance, renderer, program, tile,
                    hasShouldRenderObject, hasRenderRailStatic, hasRenderRailDynamic, railName, label, sources);
            transferred = true;
            return runtime;
        } finally {
            if (!transferred) {
                if (instance != null) instance.close();
                renderer.release();
                tile.close();
            }
        }
    }

    /** A failed instance stays disabled; the caller must discard its current filtered build. */
    public boolean failed() { return failed; }

    /** Capture static script commands once, then replay in Java under the chunk build budget. */
    public RailStaticSnapshot captureAssemblyStatic(float movement, double scale, double period) {
        var commands = new ArrayList<Consumer<RailScriptDrawContext>>();
        if (!captureAssembly(movement, scale, period, commands)) return null;
        var maps = tile.getAllRailMaps();
        var start = tile.getStartPoint();
        return new RailStaticSnapshot(maps[0].geometry(), new BlockPos(start[0], start[1], start[2]),
                scale, period, commands);
    }

    private RailDrawSnapshot lastDynamic;

    public RailDrawSnapshot captureAssemblyDynamic(float movement, double scale, double period) {
        if (!captureAssembly(movement, scale, period, null)) return null;
        return lastDynamic;
    }

    private boolean captureAssembly(float movement, double scale, double period,
            List<Consumer<RailScriptDrawContext>> commands) {
        if (failed) return false;
        var maps = tile.getAllRailMaps();
        var start = tile.getStartPoint();
        var startPoint = new BlockPos(start[0], start[1], start[2]);
        var context = new RailScriptDrawContext(maps[0].geometry(), startPoint, 0, 0,
                scale, period, false, this::shouldRender);
        if (commands != null) context.captureStatic(commands);
        else context.captureDynamic(lastDynamic);
        boolean completed = false;
        try {
            worldDraw = true;
            tile.movement(movement);
            renderer.context(context);
            GL11.attach(context);
            if (commands != null) {
                for (var map : maps) {
                    tile.map(map);
                    context.geometry(map.geometry());
                    phase = hasRenderRailStatic ? "renderRailStatic" : "renderStaticParts";
                    if (hasRenderRailStatic) invoke("renderRailStatic", tile, 0.0, 0.0, 0.0, 0.0f, 0);
                    else renderer.renderStaticParts(tile, 0, 0, 0);
                }
            } else {
                tile.map(maps[0]);
                phase = "renderRailDynamic";
                if (hasRenderRailDynamic) {
                    double y = maps[0].geometry().sample(0).position().y() - startPoint.getY();
                    invoke("renderRailDynamic", tile, 0.0, y, 0.0, 0.0f, 0);
                }
                lastDynamic = context.snapshot();
            }
            if (filterFailure != null) throw new IllegalStateException(filterFailure);
            completed = true;
            return true;
        } catch (Exception | LinkageError exception) {
            Throwable failure = filterFailure == null ? exception : filterFailure;
            LOGGER.warn("Turnout script {} {} failed at {}; using native turnout appearance: {}",
                    label, filterFailure == null ? phase : "shouldRenderObject",
                    RtmScript.describe(prepared, failure), failure.getMessage());
            LOGGER.debug("Turnout script capture failure", failure);
            return false;
        } finally {
            worldDraw = false;
            GL11.detach();
            context.restore();
            renderer.context(null);
            tile.unbind();
            filterFailure = null;
            if (!completed) close();
        }
    }

    /** A null result means failure; discard the whole build and use the complete static model. */
    public RailTrackMeshData buildStatic(TrackGeometry geometry, BlockPos startPoint, int chunkX, int chunkZ,
                                         double scale, double period, boolean preview) {
        if (failed) return null;
        var context = new RailScriptDrawContext(geometry, startPoint, chunkX, chunkZ, scale, period,
                preview, this::shouldRender);
        return evaluateStatic(geometry, startPoint, context, preview, context::finish);
    }

    /** One normal track owns the commands and frozen filter decisions for all of its chunks. */
    public RailStaticSnapshot captureStatic(TrackGeometry geometry, BlockPos startPoint, double scale, double period) {
        if (failed) return null;
        var commands = new ArrayList<Consumer<RailScriptDrawContext>>();
        var context = new RailScriptDrawContext(geometry, startPoint, 0, 0, scale, period,
                false, this::shouldRender);
        context.captureStatic(commands);
        return evaluateStatic(geometry, startPoint, context, false,
                () -> new RailStaticSnapshot(geometry, startPoint, scale, period, commands));
    }

    private <T> T evaluateStatic(TrackGeometry geometry, BlockPos startPoint, RailScriptDrawContext context,
                                  boolean preview, Supplier<T> result) {
        boolean completed = false;
        try {
            worldDraw = !preview;
            tile.bind(geometry, startPoint);
            renderer.context(context);
            GL11.attach(context);
            phase = hasRenderRailStatic ? "renderRailStatic" : "renderStaticParts";
            if (hasRenderRailStatic) invoke("renderRailStatic", tile, 0.0, 0.0, 0.0, 0.0f, 0);
            else renderer.renderStaticParts(tile, 0, 0, 0);
            // A JS catch around renderStaticParts must not commit a partially filtered mesh.
            if (filterFailure != null) throw new IllegalStateException(filterFailure);
            var value = result.get();
            completed = true;
            return value;
        } catch (Exception | LinkageError exception) {
            Throwable failure = filterFailure == null ? exception : filterFailure;
            String location = RtmScript.describe(prepared, failure);
            LOGGER.warn("Rail script {} {} failed at {}; rendering all static parts instead: {}",
                    label, filterFailure == null ? phase : "shouldRenderObject", location, failure.getMessage());
            LOGGER.debug("Rail script static build failure", failure);
            return null;
        } finally {
            worldDraw = false;
            GL11.detach();
            context.restore();
            renderer.context(null);
            tile.unbind();
            filterFailure = null;
            if (!completed) close();
        }
    }

    private boolean shouldRender(String partName, long index, long count) {
        if (filterFailure != null) throw new IllegalStateException(filterFailure);
        if (!hasShouldRenderObject) return true;
        String previous = phase;
        phase = "shouldRenderObject";
        try {
            Object result = invoke("shouldRenderObject", tile, partName, (double)count, (double)index);
            phase = previous;
            return !(result instanceof Boolean value) || value;
        } catch (Exception | LinkageError exception) {
            // The native builder has a non-throwing callback; preserve the script exception as the cause.
            filterFailure = exception;
            throw new IllegalStateException(exception);
        }
    }

    @Override
    public void close() {
        failed = true;
        lastDynamic = null;
        instance.close();
        renderer.release();
        tile.close();
    }
}
