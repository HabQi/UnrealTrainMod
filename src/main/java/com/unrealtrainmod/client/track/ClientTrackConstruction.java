/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.network.TrackSyncEvent;
import com.unrealtrainmod.network.payload.TrackConstructionPayload;
import com.unrealtrainmod.network.payload.TrackConstructionPayload.Feedback;
import com.unrealtrainmod.network.payload.TrackConstructionPayload.Phase;
import com.unrealtrainmod.track.geometry.LinearGrade;
import com.unrealtrainmod.track.geometry.StraightTrackGeometry;
import com.unrealtrainmod.track.geometry.TrackPoint;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/** The local player's transient preview only. The server decides every endpoint and connection. */
public final class ClientTrackConstruction {
    public static final ClientTrackConstruction INSTANCE = new ClientTrackConstruction();
    private ClientLevel world;
    private TrackConstructionPayload draft;
    private StraightTrackGeometry geometry;

    private ClientTrackConstruction() {}

    public void onSync(TrackSyncEvent event) {
        if (!(event.payload() instanceof TrackConstructionPayload payload)) return;
        var minecraft = Minecraft.getInstance();
        var player = minecraft.player;
        if (minecraft.level == null || player == null
                || !minecraft.level.dimension().location().equals(payload.dimension())) return;
        clear();
        if (payload.phase() != Phase.EMPTY) {
            if (!player.isAlive() || player.getInventory().selected != payload.slot()
                    || !player.getMainHandItem().is(UnrealTrainMod.TRACK_BUILDER.get())) return;
            world = minecraft.level;
            draft = payload;
            if (payload.phase() == Phase.READY) {
                var start = payload.start().position();
                var end = payload.end().position();
                geometry = new StraightTrackGeometry(new TrackPoint(start.x(), start.z()),
                        new TrackPoint(end.x(), end.z()), new LinearGrade(start.y(), end.y()), payload.gauge());
            }
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

    public void onTick(ClientTickEvent.Post event) {
        if (!isActive()) clear();
    }

    boolean isActive() {
        var minecraft = Minecraft.getInstance();
        var player = minecraft.player;
        return draft != null && minecraft.level == world && player != null && player.isAlive()
                && player.getInventory().selected == draft.slot()
                && player.getMainHandItem().is(UnrealTrainMod.TRACK_BUILDER.get());
    }

    TrackConstructionPayload draft() { return draft; }
    StraightTrackGeometry geometry() { return geometry; }

    public void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() == world) clear();
    }

    public void onClone(ClientPlayerNetworkEvent.Clone event) { clear(); }
    public void onLogout(ClientPlayerNetworkEvent.LoggingOut event) { clear(); }

    public void clear() {
        world = null;
        draft = null;
        geometry = null;
    }
}
