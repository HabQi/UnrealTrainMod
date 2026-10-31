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
import com.unrealtrainmod.track.TrackVerticalCurveParameters;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.track.geometry.VerticalTrackGeometry;
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
    private TrackGeometry horizontalGeometry;
    private boolean startVerticalEdited, endVerticalEdited;
    private TrackCurveParameters startCurve;
    private TrackCurveParameters endCurve;
    private TrackVerticalCurveParameters startVertical;
    private TrackVerticalCurveParameters endVertical;
    private Feedback previewFeedback;

    private ClientTrackConstruction() {}

    public void onSync(TrackSyncEvent event) {
        if (event.payload() instanceof OpenTrackCurvePayload open) {
            var minecraft = Minecraft.getInstance();
            if (minecraft.screen == null && (open.turnout()
                    ? ClientTurnoutConstruction.INSTANCE.matches(open.dimension(), open.revision())
                    : matches(open.dimension(), open.revision())) && minecraft.player != null
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

    public void editPreview(boolean start, TrackCurveParameters curve, TrackVerticalCurveParameters vertical,
                            boolean horizontalEdited, boolean verticalEdited) {
        if (draft == null) return;
        startCurve = start && horizontalEdited ? curve : draft.startCurve();
        endCurve = !start && horizontalEdited ? curve : draft.endCurve();
        if (verticalEdited && vertical != null) {
            var tangent = endpointTangent(start, vertical.referenceYaw());
            vertical = new TrackVerticalCurveParameters(vertical.grade(), vertical.length(), TrackVerticalCurveParameters.yaw(tangent));
        }
        startVertical = start && verticalEdited ? vertical : draft.startVertical();
        endVertical = !start && verticalEdited ? vertical : draft.endVertical();
        startVerticalEdited = start && verticalEdited;
        endVerticalEdited = !start && verticalEdited;
        updateGeometry();
    }

    public TrackVector endpointTangent(boolean start, double fallbackYaw) {
        return TrackPlacementGeometry.endpointTangent(draft.start().position(),
                draft.end() == null ? null : draft.end().position(), startCurve, endCurve, start, fallbackYaw,
                draft.startHorizontalDirected(), draft.endHorizontalDirected());
    }

    public boolean horizontalDirected(boolean start) {
        return draft != null && (start ? draft.startHorizontalDirected() : draft.endHorizontalDirected());
    }

    public TrackVerticalCurveParameters previewVertical(boolean start) { return start ? startVertical : endVertical; }

    public TrackVerticalCurveParameters defaultVerticalParameters(boolean start, double fallbackYaw) {
        var tangent = endpointTangent(start, fallbackYaw);
        double grade = 0, length = 1;
        if (draft.end() != null) {
            try {
                var flat = horizontalGeometry != null ? horizontalGeometry : TrackPlacementGeometry.horizontal(
                        draft.start().position(), draft.end().position(), draft.gauge(), startCurve, endCurve,
                        draft.startHorizontalDirected(), draft.endHorizontalDirected());
                grade = (draft.end().position().y() - draft.start().position().y()) / flat.getLength() * 1000;
                length = Math.clamp(flat.getLength() / 3, TrackCurveParameters.MIN_LENGTH, TrackCurveParameters.MAX_LENGTH);
                return TrackPlacementGeometry.verticalDefaults(flat, draft.start().position().y(), draft.end().position().y(),
                        startVertical, endVertical, start);
            } catch (IllegalArgumentException exception) {
                // Keep both pages editable while the draft is invalid.
            }
        }
        return new TrackVerticalCurveParameters(grade, length, TrackVerticalCurveParameters.yaw(tangent));
    }

    public void restorePreview() {
        if (draft == null) return;
        startCurve = draft.startCurve();
        endCurve = draft.endCurve();
        startVertical = draft.startVertical();
        endVertical = draft.endVertical();
        startVerticalEdited = false;
        endVerticalEdited = false;
        updateGeometry();
    }

    private void updateGeometry() {
        geometry = null;
        horizontalGeometry = null;
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
            if (startVertical != null || endVertical != null) {
                horizontalGeometry = TrackPlacementGeometry.horizontal(a, b, draft.gauge(), startCurve, endCurve,
                        draft.startHorizontalDirected(), draft.endHorizontalDirected());
                double length = Math.clamp(horizontalGeometry.getLength() / 3,
                        TrackCurveParameters.MIN_LENGTH, TrackCurveParameters.MAX_LENGTH);
                if (draft.startVerticalInferred() && !startVerticalEdited && startVertical != null) {
                    startVertical = new TrackVerticalCurveParameters(startVertical.grade(), length, startVertical.referenceYaw());
                }
                if (draft.endVerticalInferred() && !endVerticalEdited && endVertical != null) {
                    endVertical = new TrackVerticalCurveParameters(endVertical.grade(), length, endVertical.referenceYaw());
                }
                geometry = TrackPlacementGeometry.withVertical(horizontalGeometry, a.y(), b.y(), startVertical, endVertical);
            } else geometry = TrackPlacementGeometry.create(a, b, draft.gauge(), startCurve, endCurve, null, null,
                    draft.startHorizontalDirected(), draft.endHorizontalDirected());
            if (geometry.getLength() > TrackPlacementGeometry.MAX_LENGTH) {
                previewFeedback = Feedback.TOO_LONG;
            }
            if ((draft.startVerticalLocked() && (startVertical == null || startVertical.grade() != 0))
                    || (draft.endVerticalLocked() && (endVertical == null || endVertical.grade() != 0))) {
                previewFeedback = Feedback.FLAT_PORT;
            }
        } catch (VerticalTrackGeometry.InvalidHandlesException exception) {
            previewFeedback = Feedback.VERTICAL_HANDLES;
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
    TrackGeometry horizontalGeometry() { return horizontalGeometry; }
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
        horizontalGeometry = null;
        startVerticalEdited = false;
        endVerticalEdited = false;
        startCurve = null;
        endCurve = null;
        startVertical = null;
        endVertical = null;
        previewFeedback = null;
    }
}
