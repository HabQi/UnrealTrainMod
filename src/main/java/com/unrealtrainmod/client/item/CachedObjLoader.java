/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.item;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonObject;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.model.geometry.IGeometryBakingContext;
import net.neoforged.neoforge.client.model.geometry.IGeometryLoader;
import net.neoforged.neoforge.client.model.geometry.IUnbakedGeometry;
import net.neoforged.neoforge.client.model.obj.ObjLoader;
import net.neoforged.neoforge.client.model.obj.ObjModel;

/** Retains NeoForge's OBJ/material semantics; only the baked item's submission path changes. */
public final class CachedObjLoader implements IGeometryLoader<CachedObjLoader.Geometry> {
    @Override
    public Geometry read(JsonObject json, JsonDeserializationContext context) {
        return new Geometry(ObjLoader.INSTANCE.read(json, context));
    }

    public record Geometry(ObjModel delegate) implements IUnbakedGeometry<Geometry> {
        @Override
        public BakedModel bake(IGeometryBakingContext context, ModelBaker baker,
                               Function<Material, TextureAtlasSprite> sprites, ModelState state, ItemOverrides overrides) {
            var original = delegate.bake(context, baker, sprites, state, overrides);
            // Dynamic overrides select their original models through the standard item renderer.
            if (!overrides.getOverrides().isEmpty()) return original;
            var hint = context.getRenderTypeHint();
            boolean cutout = hint == null || hint.equals(ResourceLocation.withDefaultNamespace("solid"))
                    || hint.equals(ResourceLocation.withDefaultNamespace("cutout"))
                    || hint.equals(ResourceLocation.withDefaultNamespace("cutout_mipped"));
            return new CachedObjModel(original, cutout ? ItemGeometry.extract(original) : null);
        }

        @Override
        public void resolveParents(Function<ResourceLocation, UnbakedModel> models, IGeometryBakingContext context) {
            delegate.resolveParents(models, context);
        }

        @Override
        public Set<String> getConfigurableComponentNames() { return delegate.getConfigurableComponentNames(); }
    }
}
