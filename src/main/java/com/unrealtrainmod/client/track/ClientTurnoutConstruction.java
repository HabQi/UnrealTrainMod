/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.network.TrackSyncEvent;
import com.unrealtrainmod.network.payload.TurnoutConstructionPayload;
import com.unrealtrainmod.network.payload.TrackConstructionPayload.Feedback;
import com.unrealtrainmod.track.TrackCurveParameters;
import com.unrealtrainmod.track.TurnoutPlacementGeometry;
import com.unrealtrainmod.track.assembly.TrackAssemblyDefinition;
import com.unrealtrainmod.track.block.TrackBlockVolume;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/** Local reversible geometry; authoritative defaults include existing external-line tangents. */
public final class ClientTurnoutConstruction implements ResourceManagerReloadListener {
    public static final ClientTurnoutConstruction INSTANCE = new ClientTurnoutConstruction();
    private ClientLevel world;
    private TurnoutConstructionPayload draft;
    private TrackAssemblyDefinition.Geometry geometry;
    private Map<BlockPos, Integer> volume = Map.of();
    private Feedback feedback;
    private ClientTurnoutConstruction() {}

    public void onSync(TrackSyncEvent event) {
        if (!(event.payload() instanceof TurnoutConstructionPayload payload)) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !mc.level.dimension().location().equals(payload.dimension())) return;
        clear();
        if (!payload.points().isEmpty() && mc.player.isAlive()) {
            world = mc.level; draft = payload; restorePreview();
        }
        if (payload.feedback() == Feedback.CLEARED) return;
        boolean success = switch (payload.feedback()) {
            case TURNOUT_A, TURNOUT_B, READY, CREATED, CANCELLED -> true;
            default -> false;
        };
        mc.player.displayClientMessage(Component.translatable(payload.feedback().translationKey())
                .withStyle(success ? ChatFormatting.GREEN : ChatFormatting.RED), true);
    }

    public boolean matches(ResourceLocation dimension, UUID revision) {
        return draft != null && Minecraft.getInstance().level == world
                && draft.dimension().equals(dimension) && draft.revision().equals(revision);
    }

    public void restorePreview() { if (draft != null) editPreview(-1, null, false); }

    public void editPreview(int endpoint, TrackCurveParameters curve, boolean edited) {
        if (draft == null) return;
        geometry = null; volume = Map.of(); feedback = draft.validation();
        var curves = new ArrayList<TrackCurveParameters>();
        for (int i = 0; i < draft.points().size(); i++) {
            var raw = edited && endpoint == i ? curve : draft.curves().get(i);
            var automatic = draft.defaults().get(i);
            double angle = raw == null || draft.locked().get(i) ? automatic.angle() : raw.angle();
            curves.add(new TrackCurveParameters(angle, raw == null ? automatic.length() : raw.length()));
        }
        try {
            geometry = TurnoutPlacementGeometry.create(draft.points().get(0).position(),
                    draft.points().size() > 1 ? draft.points().get(1).position() : null,
                    draft.points().size() > 2 ? draft.points().get(2).position() : null, draft.gauge(),
                    curves.get(0), curves.size() > 1 ? curves.get(1) : null, curves.size() > 2 ? curves.get(2) : null,
                    curves.size() > 1 && draft.locked().get(1), curves.size() > 2 && draft.locked().get(2));
            var cells = new HashMap<BlockPos, Integer>();
            for (var parts : geometry.paths().values()) for (var part : parts) {
                TrackBlockVolume.create(part).forEach((pos, height) -> cells.merge(pos, height, Math::max));
            }
            volume = cells;
            if (edited) feedback = Feedback.START;
        } catch (IllegalArgumentException exception) { feedback = Feedback.INVALID_GEOMETRY; }
    }

    boolean isActive() {
        var player = Minecraft.getInstance().player;
        return draft != null && Minecraft.getInstance().level == world && player != null && player.isAlive()
                && (player.getMainHandItem().is(UnrealTrainMod.TURNOUT_BUILDER.get())
                || player.getMainHandItem().is(UnrealTrainMod.TRACK_WRENCH.get())
                || player.getMainHandItem().is(UnrealTrainMod.RAIL_PLACER.get()));
    }
    public void onTick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.level != world || mc.player == null || !mc.player.isAlive()) clear();
    }
    public void onUnload(LevelEvent.Unload event) { if (event.getLevel() == world) clear(); }
    public void onLogout(ClientPlayerNetworkEvent.LoggingOut event) { clear(); }
    public void onClone(ClientPlayerNetworkEvent.Clone event) { clear(); }
    @Override public void onResourceManagerReload(ResourceManager manager) { restorePreview(); }
    public void clear() { world = null; draft = null; geometry = null; volume = Map.of(); feedback = null; }
    TurnoutConstructionPayload draft() { return draft; }
    TrackAssemblyDefinition.Geometry geometry() { return geometry; }
    Map<BlockPos, Integer> volume() { return volume; }
    public Feedback previewFeedback() { return feedback; }
}
