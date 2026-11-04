/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm.script;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.function.Consumer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/** CPU-only legacy matrix scope, shared by vehicle commands and cached rail mesh generation. */
abstract class ScriptMatrixContext {
    protected final PoseStack pose;
    private final Matrix4f entryPose;
    private final Matrix3f entryNormal;
    private int depth;
    private ScriptGLList compiling;

    ScriptMatrixContext(PoseStack pose) {
        this.pose = pose;
        entryPose = new Matrix4f(pose.last().pose());
        entryNormal = new Matrix3f(pose.last().normal());
    }

    final void push() {
        if (compiling != null) { compiling.commands.add(ScriptMatrixContext::push); return; }
        pose.pushPose(); depth++;
    }

    final void pop() {
        if (compiling != null) { compiling.commands.add(ScriptMatrixContext::pop); return; }
        if (depth == 0) {
            ScriptDiagnostics.unsupported("GL11.glPopMatrix without glPushMatrix", "the extra pop is ignored");
            return;
        }
        pose.popPose();
        depth--;
    }

    final void translate(float x, float y, float z) {
        if (compiling != null) { compiling.commands.add(context -> context.translate(x, y, z)); return; }
        pose.translate(x, y, z);
    }

    final void rotate(float angle, float x, float y, float z) {
        if (compiling != null) { compiling.commands.add(context -> context.rotate(angle, x, y, z)); return; }
        double lengthSquared = (double)x * x + (double)y * y + (double)z * z;
        if (lengthSquared < 1e-12) return;
        float scale = (float)(1.0 / Math.sqrt(lengthSquared));
        pose.mulPose(new Quaternionf().rotateAxis((float)Math.toRadians(angle), x * scale, y * scale, z * scale));
    }

    final void scale(float x, float y, float z) {
        if (compiling != null) { compiling.commands.add(context -> context.scale(x, y, z)); return; }
        pose.scale(x, y, z);
    }

    final void record(Consumer<ScriptMatrixContext> command) {
        compiling.commands.add(command);
    }

    final boolean isCompiling() { return compiling != null; }

    final void startCompile(ScriptGLList list) {
        if (compiling != null || list.deleted) throw new IllegalStateException("Invalid script display-list compilation");
        list.commands.clear();
        list.compiled = false;
        compiling = list;
    }

    final void endCompile() {
        if (compiling == null) throw new IllegalStateException("No script display list is being compiled");
        if (!compiling.deleted) compiling.compiled = true;
        compiling = null;
    }

    final void callList(ScriptGLList list) {
        if (!GLHelper.isValid(list)) throw new IllegalStateException("Invalid script display list");
        if (compiling != null) { compiling.commands.add(context -> context.callList(list)); return; }
        for (var command : list.commands) command.accept(this);
    }

    abstract void brightness(int value);
    abstract void directionalLighting(boolean value);
    abstract void discard();

    final void restore() {
        if (compiling != null) { compiling.delete(); compiling = null; }
        while (depth > 0) { pose.popPose(); depth--; }
        pose.last().pose().set(entryPose);
        pose.last().normal().set(entryNormal);
        discard();
    }
}
