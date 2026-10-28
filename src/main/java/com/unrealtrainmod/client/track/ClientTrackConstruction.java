/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.client.gui.screen.TrackCurveScreen;
import com.unrealtrainmod.network.TrackSyncEvent;
import com.unrealtrainmod.network.payload.TrackConstructionPayload;
import com.unrealtrainmod.network.payload.TrackConstructionPayload.Feedback;
import com.unrealtrainmod.network.payload.TrackConstructionPayload.Phase;
import com.unrealtrainmod.network.payload.OpenTrackCurvePayload;
import com.unrealtrainmod.network.payload.TrackCurveResultPayload;
import com.unrealtrainmod.track.TrackCurveParameters;
import com.unrealtrainmod.track.TrackPlacementGeometry;
import com.unrealtrainmod.track.geometry.TrackGeometry;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/** The local player's transient preview only. The server decides every endpoint and connection. */
public final class ClientTrackConstruction {
    public static final ClientTrackConstruction INSTANCE = new ClientTrackConstruction();
    private ClientLevel world;
    private TrackConstructionPayload draft;
    private TrackGeometry geometry;
    private TrackCurveParameters startCurve;
    private TrackCurveParameters endCurve;
    private Feedback previewFeedback;

    private ClientTrackConstruction() {}

    public void onSync(TrackSyncEvent event) {
        if (event.payload() instanceof OpenTrackCurvePayload open) {
            var minecraft = Minecraft.getInstance();
            if (minecraft.screen == null && matches(open.dimension(), open.revision()) && minecraft.player != null
                    && minecraft.player.getMainHandItem().is(UnrealTrainMod.TRACK_WRENCH.get())) {
                minecraft.setScreen(new TrackCurveScreen(open));
            }
            return;
        }
        if (event.payload() instanceof TrackCurveResultPayload result) {
            if (Minecraft.getInstance().screen instanceof TrackCurveScreen screen) screen.acceptResult(result);
            return;
        }
        if (!(event.payload() instanceof TrackConstructionPayload payload)) return;
        var minecraft = Minecraft.getInstance();
        var player = minecraft.player;
        if (minecraft.level == null || player == null
                || !minecraft.level.dimension().location().equals(payload.dimension())) return;
        clear();
        if (payload.phase() != Phase.EMPTY) {
            if (!player.isAlive()) return;
            world = minecraft.level;
            draft = payload;
            restorePreview();
        }
        if (payload.feedback() == Feedback.CLEARED) return;
        boolean error = payload.feedback() != Feedback.START && payload.feedback() != Feedback.READY
                && payload.feedback() != Feedback.CREATED && payload.feedback() != Feedback.CANCELLED;
        var message = Component.translatable(payload.feedback().translationKey())
                .withStyle(error ? ChatFormatting.RED : ChatFormatting.GREEN);
        var selected = payload.end() == null ? payload.start() : payload.end();
        if (selected != null && selected.nodeId() != null) {
            message.append(Component.translatable("item.unrealtrainmod.track_builder.snapped", selected.nodeId().toString())
                    .withStyle(ChatFormatting.AQUA));
        }
        player.displayClientMessage(message, true);
    }

    public boolean matches(ResourceLocation dimension, UUID revision) {
        var minecraft = Minecraft.getInstance();
        return draft != null && minecraft.level == world && draft.dimension().equals(dimension)
                && draft.revision().equals(revision);
    }

    public void editPreview(boolean start, TrackCurveParameters curve) {
        if (draft == null) return;
        startCurve = start ? curve : draft.startCurve();
        endCurve = start ? draft.endCurve() : curve;
        updateGeometry();
    }

    public void restorePreview() {
        if (draft == null) return;
        startCurve = draft.startCurve();
        endCurve = draft.endCurve();
        updateGeometry();
    }

    private void updateGeometry() {
        geometry = null;
        previewFeedback = draft.end() == null ? Feedback.START : Feedback.READY;
        if (draft.end() == null) return;
        if (draft.validation() == Feedback.GAUGE_MISMATCH || draft.validation() == Feedback.SAME_NODE) {
            previewFeedback = draft.validation();
            return;
        }
        var a = draft.start().position();
        var b = draft.end().position();
        // Avoid constructing or drawing an arbitrarily long preview after travelling between selections.
        if (Math.hypot(Math.hypot(b.x() - a.x(), b.z() - a.z()), b.y() - a.y()) > TrackPlacementGeometry.MAX_LENGTH) {
            previewFeedback = Feedback.TOO_LONG;
            return;
        }
        try {
            geometry = TrackPlacementGeometry.create(a, b, draft.gauge(), startCurve, endCurve);
            if (geometry.getLength() > TrackPlacementGeometry.MAX_LENGTH) {
                previewFeedback = Feedback.TOO_LONG;
            }
        } catch (IllegalArgumentException exception) {
            previewFeedback = Feedback.INVALID_GEOMETRY;
        }
    }

    public Feedback previewFeedback() { return previewFeedback; }

    public void onTick(ClientTickEvent.Post event) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.level != world || minecraft.player == null || !minecraft.player.isAlive()) clear();
    }

    boolean isActive() {
        var minecraft = Minecraft.getInstance();
        var player = minecraft.player;
        return draft != null && minecraft.level == world && player != null && player.isAlive()
                && (player.getMainHandItem().is(UnrealTrainMod.TRACK_BUILDER.get())
                || player.getMainHandItem().is(UnrealTrainMod.RAIL_PLACER.get())
                || player.getMainHandItem().is(UnrealTrainMod.TRACK_WRENCH.get()));
    }

    TrackConstructionPayload draft() { return draft; }
    TrackGeometry geometry() { return geometry; }
    TrackCurveParameters startCurve() { return startCurve; }
    TrackCurveParameters endCurve() { return endCurve; }

    public void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() == world) clear();
    }

    public void onClone(ClientPlayerNetworkEvent.Clone event) { clear(); }
    public void onLogout(ClientPlayerNetworkEvent.LoggingOut event) { clear(); }

    public void clear() {
        world = null;
        draft = null;
        geometry = null;
        startCurve = null;
        endCurve = null;
        previewFeedback = null;
    }
}
