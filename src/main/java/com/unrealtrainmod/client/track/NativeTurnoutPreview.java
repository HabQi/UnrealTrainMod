/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.network.payload.TrackConstructionPayload.Feedback;
import com.unrealtrainmod.track.assembly.TrackAssemblyDefinition;
import com.unrealtrainmod.track.mesh.NativeTurnoutLayout;
import com.unrealtrainmod.track.mesh.NativeDiamondLayout;
import com.unrealtrainmod.track.model.NativeTurnoutCatalog;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** A draft-owned cached world preview; GUI and thumbnail previews use the model's complete example. */
final class NativeTurnoutPreview {
    private static TrackAssemblyDefinition.Geometry geometry;
    private static ClientRailModelManager.Model model;
    private static NativeTurnoutRenderState state;
    private static NativeDiamondRenderState diamondState;
    private static final Map<Long, TrackStaticMesh> meshes = new LinkedHashMap<>();
    private static final Set<Long> chunks = new LinkedHashSet<>();
    private static final TrackStaticBuffers buffers = new TrackStaticBuffers(false);
    private static boolean failed;
    private NativeTurnoutPreview() {}

    static void render(RenderLevelStageEvent event) {
        var construction = ClientTurnoutConstruction.INSTANCE;
        var player = Minecraft.getInstance().player;
        if (!construction.isActive() || player == null
                || !NativeTurnoutCatalog.contains(NativeTurnoutCatalog.constructionModel(player))) {
            clear(); construction.nativeProblem(null); return;
        }
        var draft = construction.draft();
        var selected = NativeTurnoutCatalog.constructionModel(player);
        if (!NativeTurnoutCatalog.supports(selected, draft.kind())) {
            clear(); construction.nativeProblem(Feedback.MODEL_UNSUPPORTED); return;
        }
        var current = construction.geometry();
        var currentModel = ClientRailModelManager.INSTANCE.model(selected);
        if (current == null || draft.points().size() != draft.kind().pointCount()) {
            clear(); construction.nativeProblem(null); return;
        }
        if (current != geometry || model != currentModel) {
            clear(); geometry = current; model = currentModel;
            construction.nativeProblem(null);
        }
        long layoutNanos = 0;
        if (!failed && state == null && diamondState == null && TrackWorldRenderer.previewBudget().available()) {
            long start = System.nanoTime();
            try {
                if (model == null) {
                    failed = true; construction.nativeProblem(Feedback.MISSING_MODEL); return;
                }
                if (NativeTurnoutCatalog.DIAMOND_R11.equals(selected)) {
                    var layout = NativeDiamondLayout.build(geometry, draft.gauge());
                    diamondState = new NativeDiamondRenderState(layout, model);
                    chunks.addAll(layout.chunks());
                } else {
                    var layout = NativeTurnoutLayout.build(geometry, draft.gauge());
                    state = new NativeTurnoutRenderState(layout, model, buffers);
                    state.movement(0);
                    for (var part : layout.parts()) chunks.add(NativeTurnoutMeshes.chunk(part.position()));
                }
                construction.nativeReady();
            } catch (NativeDiamondLayout.InvalidLayout exception) {
                failed = true;
                construction.nativeProblem(switch (exception.problem()) {
                    case INTERSECTION -> Feedback.NATIVE_DIAMOND_INTERSECTION;
                    case ANGLE -> Feedback.NATIVE_DIAMOND_ANGLE;
                    case SPACE -> Feedback.NATIVE_DIAMOND_SPACE;
                });
            } catch (NativeTurnoutLayout.InvalidLayout exception) {
                failed = true;
                construction.nativeProblem(exception.problem() == NativeTurnoutLayout.Problem.FROG
                        ? Feedback.NATIVE_FROG : Feedback.NATIVE_SPACE);
            } catch (IllegalArgumentException exception) {
                failed = true; construction.nativeProblem(Feedback.MISSING_MODEL);
                org.slf4j.LoggerFactory.getLogger(NativeTurnoutPreview.class)
                        .warn("Native turnout preview unavailable: {}", exception.getMessage());
            } finally {
                layoutNanos = System.nanoTime() - start;
                TrackWorldRenderer.previewBudget().pageFinished(start);
            }
        }
        if (state != null || diamondState != null) {
            construction.nativeReady();
            try {
                if (diamondState != null) TrackWorldRenderer.renderDiamondPreview(event, diamondState, meshes, chunks, buffers, layoutNanos);
                else TrackWorldRenderer.renderNativePreview(event, state, meshes, chunks, buffers, layoutNanos);
            } catch (IllegalArgumentException exception) {
                clear(); failed = true; geometry = current; model = currentModel;
                construction.nativeProblem(Feedback.MISSING_MODEL);
                org.slf4j.LoggerFactory.getLogger(NativeTurnoutPreview.class)
                        .warn("Native construction preview unavailable: {}", exception.getMessage());
            }
        }
    }

    static void invalidateGpu() {
        buffers.close();
        if (state != null) state.invalidateGpu();
    }
    static void clear() {
        buffers.close(); meshes.clear(); chunks.clear();
        if (state != null) state.close();
        state = null; diamondState = null; geometry = null; model = null; failed = false;
    }
}
