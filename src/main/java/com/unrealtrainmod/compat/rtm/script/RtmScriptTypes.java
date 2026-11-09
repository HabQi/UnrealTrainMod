/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm.script;

import java.util.List;

/** Single source for legacy names, replacement types and pre-bound package-import names. */
final class RtmScriptTypes {
    private record Type(String legacyName, Class<?> implementation, String name) {
        String alias() { return "__UTM_" + name; }
    }

    private static final List<Type> TYPES = List.of(
            new Type("org.lwjgl.opengl.GL11", GL11.class, "GL11"),
            new Type("jp.ngt.rtm.render.Parts", Parts.class, "Parts"),
            new Type("jp.ngt.rtm.rail.util.RailDir", RailDir.class, "RailDir"),
            new Type("jp.ngt.rtm.rail.util.RailPosition", RailPosition.class, "RailPosition"),
            new Type("jp.ngt.ngtlib.renderer.TESRRenderStats", TESRRenderStats.class, "TESRRenderStats"),
            new Type("jp.ngt.ngtlib.renderer.GLHelper", GLHelper.class, "GLHelper"),
            new Type("jp.ngt.ngtlib.renderer.NGTTessellator", NGTTessellator.class, "NGTTessellator"),
            new Type("jp.ngt.rtm.entity.train.util.TrainState", RtmTrainState.class, "TrainState"),
            new Type("jp.ngt.rtm.RTMCore", RtmCore.class, "RTMCore"),
            new Type("jp.ngt.ngtlib.math.NGTMath", NGTMath.class, "NGTMath"));

    private RtmScriptTypes() {}

    static String globals() {
        var source = new StringBuilder();
        for (var type : TYPES) {
            source.append("var ").append(type.alias()).append(" = Java.type('")
                    .append(type.implementation().getName()).append("');\nvar ")
                    .append(type.name()).append(" = ").append(type.alias()).append(";\n");
        }
        return source.toString();
    }

    static String aliasOf(String name) {
        String qualified = name.startsWith("Packages.") ? name.substring("Packages.".length()) : name;
        for (var type : TYPES) if (type.legacyName().equals(qualified)) return type.alias();
        return null;
    }
}
