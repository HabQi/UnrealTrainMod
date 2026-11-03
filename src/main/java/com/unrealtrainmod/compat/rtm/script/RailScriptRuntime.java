/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm.script;

import com.unrealtrainmod.resource.PackageAsset;
import java.io.IOException;
import java.util.ArrayList;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One legacy rail script used to select static parts for a rail model variant. */
public final class RailScriptRuntime implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("UnrealTrainMod RTM scripts");

    private final RtmScriptInstance instance;
    private final RailScriptRenderer renderer;
    private final RtmScriptSource.Prepared prepared;
    private final RailScriptTile tile;
    private final boolean hasShouldRenderObject;
    private final String label;
    private boolean failed;

    private RailScriptRuntime(RtmScriptInstance instance, RailScriptRenderer renderer,
                              RtmScriptSource.Prepared prepared, RailScriptTile tile,
                              boolean hasShouldRenderObject, String label) {
        this.instance = instance;
        this.renderer = renderer;
        this.prepared = prepared;
        this.tile = tile;
        this.hasShouldRenderObject = hasShouldRenderObject;
        this.label = label;
    }

    public static RailScriptRuntime create(RtmScriptCache cache, PackageAsset script, String railName, String label)
            throws IOException {
        var program = cache.program(script);
        var renderer = new RailScriptRenderer();
        var tile = new RailScriptTile(railName);
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
            var notExecuted = new ArrayList<String>(2);
            if (instance.hasFunction("renderRailStatic")) notExecuted.add("renderRailStatic");
            if (instance.hasFunction("renderRailDynamic")) notExecuted.add("renderRailDynamic");
            ScriptDiagnostics.scriptScope("rail", script, renderClass
                    + (hasShouldRenderObject
                            ? "; shouldRenderObject selects the static parts" : "; no shouldRenderObject, every static part is kept")
                    + (notExecuted.isEmpty() ? "" : "; not executed in this stage: " + String.join(", ", notExecuted)));
            var runtime = new RailScriptRuntime(instance, renderer, program.prepared, tile, hasShouldRenderObject, label);
            transferred = true;
            return runtime;
        } finally {
            if (!transferred) {
                if (instance != null) instance.close();
                renderer.release();
            }
        }
    }

    /** A failed instance stays disabled; the caller must discard its current filtered build. */
    public boolean failed() { return failed; }

    /** Each invocation restores its segment context on the tile also exposed as global tileEntity. */
    public boolean shouldRender(String partName, long index, long count, BlockPos startPoint) {
        if (failed || !hasShouldRenderObject) return true;
        boolean completed = false;
        try {
            tile.setStartPoint(startPoint);
            Object result = instance.invoke("shouldRenderObject", tile, partName, (double)count, (double)index);
            completed = true;
            if (result instanceof Boolean value) return value;
            // Missing/undefined results follow the legacy renderer: keep the object.
            return true;
        } catch (Exception | LinkageError exception) {
            String location = RtmScript.describe(prepared, exception);
            LOGGER.warn("Rail script {} shouldRenderObject failed at {}; rendering all static parts instead: {}",
                    label, location, exception.getMessage());
            LOGGER.debug("Rail script shouldRenderObject failure", exception);
            return true;
        } finally {
            if (!completed) close();
        }
    }

    @Override
    public void close() {
        failed = true;
        instance.close();
        renderer.release();
    }
}
