/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm.script;

import com.mojang.blaze3d.vertex.PoseStack;
import com.unrealtrainmod.client.model.LoadedModel;
import com.unrealtrainmod.client.model.PartSelection;
import com.unrealtrainmod.client.renderer.ModelRenderQueue;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * Collects transforms and part draws for one script invocation. The caller's entry transform is copied up front
 * and restored on every exit path, so a script cannot leak its own pushes, pops or direct transforms into the
 * static fallback. Commands are only submitted after the script returns successfully.
 */
final class ScriptDrawContext {
    private record Command(PartSelection selection, PoseStack.Pose pose) {}

    private final ModelRenderQueue queue;
    private final LoadedModel.Source source;
    private final PoseStack pose;
    private final String modelLabel;
    /** Independent baseline of the caller matrix; the script never owns this level of the stack. */
    private final Matrix4f entryPose;
    private final Matrix3f entryNormal;
    private final List<Command> commands = new ArrayList<>();
    private final Map<String, PartSelection> resolved = new HashMap<>();
    private int depth;

    ScriptDrawContext(ModelRenderQueue queue, LoadedModel.Source source, PoseStack pose, String modelLabel) {
        this.queue = queue;
        this.source = source;
        this.pose = pose;
        this.modelLabel = modelLabel;
        entryPose = new Matrix4f(pose.last().pose());
        entryNormal = new Matrix3f(pose.last().normal());
    }

    void push() {
        pose.pushPose();
        depth++;
    }

    void pop() {
        if (depth == 0) {
            ScriptDiagnostics.unsupported("GL11.glPopMatrix without glPushMatrix", "the extra pop is ignored");
            return;
        }
        pose.popPose();
        depth--;
    }

    void translate(float x, float y, float z) { pose.translate(x, y, z); }

    void rotate(float angle, float x, float y, float z) {
        double lengthSquared = (double)x * x + (double)y * y + (double)z * z;
        if (lengthSquared < 1e-12) return;
        float scale = (float)(1.0 / Math.sqrt(lengthSquared));
        pose.mulPose(new Quaternionf().rotateAxis((float)Math.toRadians(angle), x * scale, y * scale, z * scale));
    }

    void scale(float x, float y, float z) { pose.scale(x, y, z); }

    void draw(List<String> names) {
        PoseStack.Pose snapshot = null;
        for (String name : names) {
            var selection = selection(name);
            if (selection == null) continue;
            if (snapshot == null) snapshot = pose.last().copy();
            commands.add(new Command(selection, snapshot));
        }
    }

    private PartSelection selection(String name) {
        var cached = resolved.get(name);
        if (cached != null) return cached;
        var selection = source.partsByName().get(name);
        if (selection == null) {
            // MQO group names are case-sensitive on disk but packs are inconsistent about case.
            for (var entry : source.partsByName().entrySet()) {
                if (entry.getKey().trim().equalsIgnoreCase(name.trim())) {
                    selection = entry.getValue();
                    break;
                }
            }
        }
        if (selection == null) {
            ScriptDiagnostics.missingPart(modelLabel, name);
            return null;
        }
        resolved.put(name, selection);
        return selection;
    }

    void submit(int light) {
        for (var command : commands) queue.submitGeometry(source, command.selection(), command.pose(), light);
    }

    /**
     * Drop unclosed pushes, pending commands and every script transform. The caller then draws its static fallback
     * from the same pose it submitted with.
     */
    void restore() {
        while (depth > 0) {
            pose.popPose();
            depth--;
        }
        pose.last().pose().set(entryPose);
        pose.last().normal().set(entryNormal);
        commands.clear();
    }
}
