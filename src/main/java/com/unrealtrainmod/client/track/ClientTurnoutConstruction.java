/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.client.gui.screen.TurnoutConstructionScreen;
import com.unrealtrainmod.network.TrackSyncEvent;
import com.unrealtrainmod.network.payload.OpenTurnoutPayload;
import com.unrealtrainmod.network.payload.TurnoutActionPayload;
import com.unrealtrainmod.network.payload.TurnoutResultPayload;
import com.unrealtrainmod.network.payload.TurnoutResultPayload.Feedback;
import com.unrealtrainmod.track.assembly.TrackAssemblyDefinition;
import com.unrealtrainmod.track.block.TrackBlockVolume;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Private preview data; no persistent mesh or entries in the formal track cache. */
public final class ClientTurnoutConstruction implements ResourceManagerReloadListener {
    public static final ClientTurnoutConstruction INSTANCE = new ClientTurnoutConstruction();
    private OpenTurnoutPayload draft;
    private TrackAssemblyDefinition.Geometry geometry;
    private Map<BlockPos, Integer> volume = Map.of();
    private Feedback feedback;
    private double yaw;
    private TrackAssemblyDefinition definition;
    private ClientTurnoutConstruction() {}

    public void onSync(TrackSyncEvent event) {
        var mc = Minecraft.getInstance();
        if (event.payload() instanceof OpenTurnoutPayload open) {
            if (mc.level == null || mc.player == null || !mc.level.dimension().location().equals(open.dimension())) return;
            if (mc.screen != null || !mc.player.isAlive() || !mc.player.hasPermissions(2)
                    || !mc.player.getMainHandItem().is(UnrealTrainMod.TURNOUT_BUILDER.get())) {
                PacketDistributor.sendToServer(new TurnoutActionPayload(open.dimension(), open.draftId(), 1,
                        TurnoutActionPayload.Action.CANCEL, open.definition(), open.yaw()));
                return;
            }
            draft = open;
            update(open.definition(), open.yaw(), open.feedback());
            mc.setScreen(new TurnoutConstructionScreen(open));
        } else if (event.payload() instanceof TurnoutResultPayload result
                && mc.screen instanceof TurnoutConstructionScreen screen) {
            screen.accept(result);
        }
    }

    public void update(TrackAssemblyDefinition definition, double yaw, Feedback feedback) {
        this.feedback = feedback;
        if (draft == null || (geometry != null && this.definition == definition && this.yaw == yaw)) return;
        this.definition = definition; this.yaw = yaw; geometry = null; volume = Map.of();
        if (!Double.isFinite(yaw) || draft.gauge() <= 0 || 32 * draft.gauge() / 1.435 > 256) return;
        geometry = definition.geometry(draft.origin().position(), yaw, draft.gauge());
        try {
            var cells = new HashMap<>(TrackBlockVolume.create(geometry.straight()));
            TrackBlockVolume.create(geometry.diverging()).forEach((pos, height) -> cells.merge(pos, height, Math::max));
            volume = cells;
        } catch (IllegalArgumentException exception) {
            // A red geometry can still explain a size/position rejection, without enumerating its footprint.
            volume = Map.of();
        }
    }

    public void onTick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (draft != null && (mc.level == null || !mc.level.dimension().location().equals(draft.dimension())
                || mc.player == null || !mc.player.isAlive()
                || !(mc.screen instanceof TurnoutConstructionScreen))) clear();
    }
    public void onUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) clear();
    }
    public void onLogout(ClientPlayerNetworkEvent.LoggingOut event) { clear(); }
    public void onClone(ClientPlayerNetworkEvent.Clone event) { clear(); }
    @Override public void onResourceManagerReload(ResourceManager manager) { closeScreen(); }
    private void closeScreen() {
        if (Minecraft.getInstance().screen instanceof TurnoutConstructionScreen screen) screen.onClose();
        clear();
    }
    public void clear() { draft = null; geometry = null; volume = Map.of(); feedback = null; definition = null; }
    public boolean matches(UUID id) { return draft != null && draft.draftId().equals(id); }
    OpenTurnoutPayload draft() { return draft; }
    TrackAssemblyDefinition.Geometry geometry() { return geometry; }
    Map<BlockPos, Integer> volume() { return volume; }
    Feedback feedback() { return feedback; }
    double yaw() { return yaw; }
    TrackAssemblyDefinition definition() { return definition; }
}
