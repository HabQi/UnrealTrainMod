/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.track;

import com.google.gson.GsonBuilder;
import com.unrealtrainmod.model.IndexedMesh;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import static net.minecraft.commands.Commands.literal;

/** Explicit local diagnostic export of the actual CPU meshes; never executes during normal rendering. */
public final class NativeScissorsExport {
    private NativeScissorsExport() {}
    public static void register(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(literal("utm_scissors_export")
                .executes(context -> export()));
    }
    private static int export() {
        var mc = Minecraft.getInstance(); if (mc.player == null) return 0;
        NativeCrossoverRenderState state = null; String name = null;
        double nearest = Double.POSITIVE_INFINITY;
        for (var entry : ClientTrackCache.INSTANCE.assemblies.entrySet()) {
            var value = entry.getValue().crossoverState;
            if (value == null || value.layout.kind() != com.unrealtrainmod.track.mesh.NativeCrossoverLayout.Kind.SCISSORS) continue;
            var bounds = value.layout.bounds(); var center = bounds.getCenter();
            double distance = mc.player.position().distanceToSqr(center);
            if (distance < nearest) { nearest = distance; state = value; name = entry.getKey().toString(); }
        }
        if (state == null) { mc.player.displayClientMessage(Component.literal("No prepared native scissors layout to export."),false); return 0; }
        var root = mc.gameDirectory.toPath().resolve("debug/unrealtrainmod/scissors").resolve(name);
        try {
            write(state,root);
            mc.player.displayClientMessage(Component.literal("Scissors meshes exported: "+root.toAbsolutePath()),false); return 1;
        } catch (IOException failure) {
            org.slf4j.LoggerFactory.getLogger(NativeScissorsExport.class).error("Cannot export native scissors to {}",root,failure);
            mc.player.displayClientMessage(Component.literal("Scissors export failed: "+failure.getMessage()),false); return 0;
        }
    }
    private static void write(NativeCrossoverRenderState state, Path root) throws IOException {
        Files.createDirectories(root); var manifest = new LinkedHashMap<String,Object>(); var meshes = new ArrayList<Object>();
        try (var stream = Minecraft.getInstance().getResourceManager().getResourceOrThrow(NativeTurnoutModels.TEXTURE).open()) {
            Files.copy(stream,root.resolve("r11.png"),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        var geometry = state.meshes().geometry();
        for (var entry : geometry.fixed().entrySet()) meshes.add(mesh(root,entry.getKey(),entry.getValue(),null));
        var switches = state.meshes().switches();
        for (int control = 0; control < 4; control++) {
            for (var part : switches.get(state.layout.kind().controls().get(control)).parts()) {
                var matrices = new ArrayList<Object>();
                for (float movement : new float[]{0,.5f,1}) matrices.add(Map.of("movement",movement,"matrix",part.transform(state.layout.origin(),state.layout.scale(),movement).get(new float[16])));
                meshes.add(mesh(root,part.name(),geometry.blades().get(part.name()),Map.of("control",control,"poses",matrices)));
            }
        }
        manifest.put("qualification","Actual runtime CPU geometry; big-endian int vertexCount/indexCount, float XYZ/UV/normal, int indices. Does not validate GPU or shader output.");
        manifest.put("recipe",NativeCrossoverModels.RECIPE); manifest.put("origin",state.layout.origin()); manifest.put("scale",state.layout.scale());
        manifest.put("paths",state.layout.paths()); manifest.put("connections",state.layout.connections());
        manifest.put("fixedRails",state.layout.fixedRails());
        manifest.put("sleepers",state.meshes().sleepers()); manifest.put("fixedParts",state.layout.fixedParts()); manifest.put("meshes",meshes);
        Files.writeString(root.resolve("manifest.json"),new GsonBuilder().setPrettyPrinting().create().toJson(manifest));
    }
    private static Object mesh(Path root, String name, IndexedMesh mesh, Object animation) throws IOException {
        String file = name+".bin";
        try (var stream = new DataOutputStream(Files.newOutputStream(root.resolve(file)))) {
            stream.writeInt(mesh.colors().length); stream.writeInt(mesh.indices().length);
            for (float value : mesh.vertices()) stream.writeFloat(value);
            for (int value : mesh.indices()) stream.writeInt(value);
        }
        var record = new LinkedHashMap<String,Object>(); record.put("name",name); record.put("file",file);
        record.put("triangles",mesh.indices().length/3); if (animation != null) record.put("animation",animation); return record;
    }
}
