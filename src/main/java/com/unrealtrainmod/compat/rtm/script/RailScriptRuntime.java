/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm.script;

import com.unrealtrainmod.resource.PackageAsset;
import java.io.IOException;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One legacy rail script used to select static parts for a rail model variant. */
public final class RailScriptRuntime implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("UnrealTrainMod RTM scripts");

    private final ScriptEngine engine;
    private final RtmScriptSource.Prepared prepared;
    private final RailScriptTile tile;
    private final boolean hasShouldRenderObject;
    private final String label;
    private boolean failed;

    private RailScriptRuntime(ScriptEngine engine, RtmScriptSource.Prepared prepared, RailScriptTile tile,
                              boolean hasShouldRenderObject, String label) {
        this.engine = engine;
        this.prepared = prepared;
        this.tile = tile;
        this.hasShouldRenderObject = hasShouldRenderObject;
        this.label = label;
    }

    public static RailScriptRuntime create(PackageAsset script, String railName, String label)
            throws IOException, ScriptException, NoSuchMethodException {
        var prepared = RtmScriptSource.load(script);
        ScriptEngine engine = RtmScript.createEngine();
        var renderer = new RailScriptRenderer();
        engine.put("renderer", renderer);
        var tile = new RailScriptTile(railName);
        engine.put("tileEntity", tile);
        try {
            RtmScript.eval(engine, prepared);
        } catch (ScriptException exception) {
            throw new IOException("Script error at " + RtmScript.describe(prepared, exception)
                    + ": " + exception.getMessage(), exception);
        }
        String renderClass = RtmScript.renderClass(engine);
        if (renderClass.isEmpty()) throw new IOException("Script has no renderClass");
        if (!RtmScript.RAIL_RENDERER.equals(renderClass)) {
            throw new IOException("Unsupported renderClass '" + renderClass + "'; expected " + RtmScript.RAIL_RENDERER);
        }
        if (!RtmScript.hasFunction(engine, "init")) throw new IOException("Script has no init(par1, par2)");
        try {
            RtmScript.invoke(engine, "init", null, null);
        } catch (ScriptException exception) {
            throw new IOException("init failed at " + RtmScript.describe(prepared, exception)
                    + ": " + exception.getMessage(), exception);
        }
        return new RailScriptRuntime(engine, prepared, tile,
                RtmScript.hasFunction(engine, "shouldRenderObject"), label);
    }

    /** A failed instance stays disabled; the caller must discard its current filtered build. */
    public boolean failed() { return failed; }

    /** Called for every repeated module and every prepared part, matching the old static render loop. */
    public boolean shouldRender(String partName, long index, long count) {
        if (failed || !hasShouldRenderObject) return true;
        try {
            Object result = RtmScript.invoke(engine, "shouldRenderObject", tile, partName, (double)count, (double)index);
            if (result instanceof Boolean value) return value;
            // Missing/undefined results follow the legacy renderer: keep the object.
            return true;
        } catch (Exception exception) {
            failed = true;
            String location = exception instanceof ScriptException script
                    ? RtmScript.describe(prepared, script) : prepared.primaryFile();
            LOGGER.warn("Rail script {} failed at {}; rendering all static parts instead: {}",
                    label, location, exception.getMessage());
            LOGGER.debug("Rail script shouldRenderObject failure", exception);
            return true;
        }
    }

    @Override
    public void close() {
        failed = true;
        engine.getBindings(ScriptContext.ENGINE_SCOPE).clear();
    }
}
