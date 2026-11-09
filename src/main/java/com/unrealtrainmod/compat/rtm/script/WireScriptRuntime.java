/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.compat.rtm.script;

import com.mojang.blaze3d.vertex.PoseStack;
import com.unrealtrainmod.client.wiring.WiringGeometry;
import com.unrealtrainmod.client.wiring.WiringModelManager;
import com.unrealtrainmod.wiring.ConnectorData;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.LoggerFactory;

/** Per-connection script state. Static callbacks are invalidated by either endpoint's pose. */
public final class WireScriptRuntime implements AutoCloseable {
    private final WiringModelManager.Model model;
    private final RtmScriptProgram program;
    private final RtmScriptInstance instance;
    private final WireScriptRenderer renderer;
    private final List<Integer> passes;
    private List<WiringGeometry.Draw> staticDraws;
    private ConnectorData lastFirst, lastSecond;
    private boolean failed;

    public WireScriptRuntime(RtmScriptCache cache, WiringModelManager.Model model) throws IOException {
        this.model = model; program = cache.program(model.definition().renderer());
        instance = new RtmScriptInstance(program); renderer = new WireScriptRenderer(model, instance);
        var ps = new java.util.TreeSet<Integer>(); ps.add(0); ps.add(model.firstTexture().pass());
        model.batches().forEach(b -> ps.add(b.material().pass())); passes = List.copyOf(ps);
        try {
            instance.put("renderer", renderer); instance.eval();
            if (!instance.renderClass().equals("jp.ngt.rtm.render.WirePartsRenderer")) {
                throw new IOException("Expected wire renderClass jp.ngt.rtm.render.WirePartsRenderer");
            }
            if (!instance.hasFunction("init")) throw new IOException("Wire script is missing init");
            instance.init();
            if (!instance.hasFunction("renderWireStatic") && !instance.hasFunction("renderWireDynamic")) {
                throw new IOException("Wire script has no renderWireStatic/renderWireDynamic callback");
            }
        } catch (IOException | RuntimeException | LinkageError e) { close(); throw e; }
    }

    /** Null requests native fallback; an empty list is a successful script choosing to draw nothing. */
    public List<WiringGeometry.Draw> render(ConnectorData first, ConnectorData second, long tick, float partialTick) {
        if (failed) return null;
        var tile = new WireScriptViews.Tile(first, tick);
        var connection = new WireScriptViews.Connection(model.definition(), second);
        var target = new WireScriptViews.Vector(second.wirePoint().subtract(first.wirePoint()));
        try {
            if (!first.equals(lastFirst) || !second.equals(lastSecond)) {
                staticDraws = callback("renderWireStatic", tile, connection, target, partialTick);
                lastFirst = first; lastSecond = second;
            }
            var result = new ArrayList<WiringGeometry.Draw>(staticDraws);
            result.addAll(callback("renderWireDynamic", tile, connection, target, partialTick));
            return List.copyOf(result);
        } catch (Exception | LinkageError exception) {
            LoggerFactory.getLogger(WireScriptRuntime.class).warn("Wire script {} failed at {}: {}",
                    model.definition().config(), RtmScript.describe(program.prepared, exception), exception.getMessage());
            close(); return null;
        }
    }
    private List<WiringGeometry.Draw> callback(String name, Object tile, Object connection, WireScriptViews.Vector target, float partialTick)
            throws Exception {
        if (!instance.hasFunction(name)) return List.of();
        var output = new ArrayList<WiringGeometry.Draw>();
        for (int pass : passes) {
            var context = new WireDrawContext(new PoseStack(), model, pass); GL11.attach(context);
            try {
                instance.invoke(name, tile, connection, target, partialTick, pass);
                output.addAll(context.finish());
            } finally { GL11.detach(); context.restore(); }
        }
        return List.copyOf(output);
    }
    @Override public void close() {
        failed = true; instance.close(); renderer.release(); staticDraws = null; lastFirst = null; lastSecond = null;
    }
}
