/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.compat.rtm.script;

import com.unrealtrainmod.client.wiring.WiringModelManager;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

/** Independently implemented WirePartsRenderer subset used by the package wire scripts. */
public final class WireScriptRenderer extends RtmPartsRenderer {
    public static final class Texture { public final Material material; Texture(ResourceLocation id) { material = new Material(id); } }
    public static final class Material { public final ResourceLocation texture; Material(ResourceLocation id) { texture = id; } }
    public static final class ModelObject { public final Texture[] textures; ModelObject(ResourceLocation id) { textures = new Texture[]{new Texture(id)}; } }
    private final WiringModelManager.Model model;
    private final ModelObject object;
    private final RtmScriptInstance instance;
    WireScriptRenderer(WiringModelManager.Model model, RtmScriptInstance instance) {
        this.model = model; this.instance = instance; object = new ModelObject(model.firstTexture().texture());
    }
    private WireDrawContext context() { return (WireDrawContext)GL11.current(); }
    @Override protected void draw(List<String> names) { context().parts(names); }
    @Override protected void registered(Parts parts) {
        for (String name : parts.names()) if (model.batches().stream().noneMatch(b -> b.part().strip().equalsIgnoreCase(name.strip()))) {
            ScriptDiagnostics.missingPart(model.definition().config().toString(), name);
        }
    }
    public ModelObject getModelObject() { return object; }
    public String[] getAllObjNames() { return model.batches().stream().map(b -> b.part()).distinct().toArray(String[]::new); }
    public void rotate(float angle, char axis, float x, float y, float z) {
        var c = context(); c.translate(x, y, z);
        switch (axis) { case 'X' -> c.rotate(angle, 1, 0, 0); case 'Y' -> c.rotate(angle, 0, 1, 0); case 'Z' -> c.rotate(angle, 0, 0, 1); default -> { } }
        c.translate(-x, -y, -z);
    }
    public void renderWireStraight(Object tile, Object connection, WireScriptViews.Vector vector, float partialTick, int pass, Parts parts) {
        repeat(tile, vector, pass, parts, false);
    }
    public void renderWireDeflection(Object tile, Object connection, WireScriptViews.Vector vector, float partialTick, int pass, Parts parts) {
        repeat(tile, vector, pass, parts, true);
    }
    private void repeat(Object tile, WireScriptViews.Vector vector, int pass, Parts parts, boolean sag) {
        context().repeat(vector.nativeVector(), sag, parts, (count, index) -> {
            try {
                return !instance.hasFunction("shouldRenderObject") || Boolean.TRUE.equals(instance.invoke("shouldRenderObject", tile, count, index, pass));
            } catch (Exception e) { throw new IllegalStateException("shouldRenderObject failed", e); }
        });
    }
    public long getTick(Object tile) { return ((WireScriptViews.Tile)tile).getTick(); }
    @Override public void bindTexture(Object texture) {
        if (!object.textures[0].material.texture.equals(texture)) {
            ScriptDiagnostics.unsupported("wire renderer.bindTexture", "wire parts retain their configured material textures");
        }
    }
}
