/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.model;

import com.unrealtrainmod.track.assembly.TrackAssemblyInstance;
import com.unrealtrainmod.track.assembly.TrackAssemblyType;
import com.unrealtrainmod.track.construction.TrackConstructionType;
import net.minecraft.resources.ResourceLocation;

/** Built-in style identities are shared by the server; their visual assets are client resources. */
public final class NativeTurnoutCatalog {
    public static final ResourceLocation DEFAULT = ResourceLocation.parse("unrealtrainmod:native_turnout/default");

    private NativeTurnoutCatalog() {}

    public static boolean contains(ResourceLocation id) { return DEFAULT.equals(id); }

    public static boolean known(ResourceLocation id) { return contains(id) || RailCatalog.find(id) != null; }

    public static boolean supports(ResourceLocation id, TrackConstructionType kind) {
        if (contains(id)) return kind.implemented();
        var definition = RailCatalog.find(id);
        return definition != null && (definition.source().format() != RailDefinition.Format.MQO
                || kind == TrackConstructionType.SIMPLE_TURNOUT);
    }

    public static boolean usesNative(TrackAssemblyInstance assembly) {
        String id = assembly.modelId();
        if (id == null || contains(ResourceLocation.parse(id))) return true;
        // Stable RTM IDs also cover old saves when their original package is no longer installed.
        return assembly.definition().type() != TrackAssemblyType.SIMPLE_TURNOUT
                && id.startsWith("unrealtrainmod:rtm/rail/");
    }
}
