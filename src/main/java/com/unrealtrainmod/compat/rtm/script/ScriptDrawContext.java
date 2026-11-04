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
import java.util.HashSet;
import java.util.List;

/**
 * Collects transforms and part draws for one script invocation. The caller's entry transform is copied up front
 * and restored on every exit path, so a script cannot leak its own pushes, pops or direct transforms into the
 * static fallback. Commands are only submitted after the script returns successfully.
 */
final class ScriptDrawContext extends ScriptMatrixContext {
    private record Command(PartSelection selection, PoseStack.Pose pose, int light, boolean directionalLighting) {}

    private final ModelRenderQueue queue;
    private final LoadedModel.Source source;
    private final ScriptPartResolver parts;
    private final List<Command> commands = new ArrayList<>();
    private int light;
    private boolean directionalLighting = true;

    ScriptDrawContext(ModelRenderQueue queue, LoadedModel.Source source, ScriptPartResolver parts, PoseStack pose, int light) {
        super(pose);
        this.queue = queue;
        this.source = source;
        this.parts = parts;
        this.light = light;
    }

    @Override
    void brightness(int value) { light = value; }

    @Override
    void directionalLighting(boolean value) { directionalLighting = value; }

    void draw(List<String> names) {
        PoseStack.Pose snapshot = null;
        // Aliases within one group may resolve to the same objects; separate calls still draw independently.
        var drawn = new HashSet<PartSelection>();
        for (String name : names) {
            var selection = parts.selection(name);
            if (selection == null || !drawn.add(selection)) continue;
            if (snapshot == null) snapshot = pose.last().copy();
            commands.add(new Command(selection, snapshot, light, directionalLighting));
        }
    }

    void submit() {
        for (var command : commands) {
            queue.submitGeometry(source, command.selection(), command.pose(), command.light(), command.directionalLighting());
        }
    }

    @Override
    void discard() {
        commands.clear();
    }
}
