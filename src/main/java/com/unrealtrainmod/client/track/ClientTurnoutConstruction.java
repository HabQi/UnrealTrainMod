/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.item.TurnoutBuilderItem;
import com.unrealtrainmod.network.TrackSyncEvent;
import com.unrealtrainmod.network.payload.TurnoutConstructionPayload;
import com.unrealtrainmod.network.payload.TrackConstructionPayload.Feedback;
import com.unrealtrainmod.track.TrackCurveParameters;
import com.unrealtrainmod.track.TrackPlacementEndpoint;
import com.unrealtrainmod.track.AssemblyPlacementGeometry;
import com.unrealtrainmod.track.assembly.TrackAssemblyDefinition;
import com.unrealtrainmod.track.block.TrackBlockVolume;
import com.unrealtrainmod.track.geometry.TrackVector;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

/** Local reversible geometry using the same directed port handles as server placement. */
public final class ClientTurnoutConstruction implements ResourceManagerReloadListener {
    public static final ClientTurnoutConstruction INSTANCE = new ClientTurnoutConstruction();
    private ClientLevel world;
    private TurnoutConstructionPayload draft;
    private TrackAssemblyDefinition.Geometry geometry;
    private Map<BlockPos, Integer> volume = Map.of();
    private Feedback feedback;
    private List<TrackCurveParameters> previewCurves = List.of();
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
            case TURNOUT_A, TURNOUT_B, CROSSING_POINT, CROSSOVER_POINT, READY, CREATED, CANCELLED -> true;
            default -> false;
        };
        var message = payload.feedback() == Feedback.CROSSING_POINT || payload.feedback() == Feedback.CROSSOVER_POINT
                ? Component.translatable(payload.feedback().translationKey(), payload.points().size(), payload.kind().pointCount())
                : Component.translatable(payload.feedback().translationKey());
        mc.player.displayClientMessage(message
                .withStyle(success ? ChatFormatting.GREEN : ChatFormatting.RED), true);
    }

    public boolean matches(ResourceLocation dimension, UUID revision) {
        return draft != null && Minecraft.getInstance().level == world
                && draft.dimension().equals(dimension) && draft.revision().equals(revision);
    }

    public void restorePreview() { if (draft != null) editPreview(-1, null, false); }

    public void editPreview(int endpoint, TrackCurveParameters curve, boolean edited) {
        if (draft == null) return;
        geometry = null; volume = Map.of(); feedback = draft.validation(); previewCurves = List.of();
        var curves = new ArrayList<>(draft.curves());
        if (edited) curves.set(endpoint, curve);
        var points = draft.points().stream().map(TrackPlacementEndpoint::position).toList();
        previewCurves = AssemblyPlacementGeometry.defaults(draft.kind(), points, curves, draft.yaw());
        try {
            geometry = AssemblyPlacementGeometry.create(draft.kind(), points, draft.gauge(), curves, draft.yaw());
            volume = footprint(geometry);
            if (draft.points().size() == draft.kind().pointCount()) {
                AssemblyPlacementGeometry.validate(AssemblyPlacementGeometry.definition(draft.kind()), geometry);
            }
            if (edited) feedback = Feedback.START;
        } catch (IllegalArgumentException exception) { feedback = Feedback.INVALID_GEOMETRY; }
    }

    private static Map<BlockPos, Integer> footprint(TrackAssemblyDefinition.Geometry geometry) {
        var cells = new HashMap<BlockPos, Integer>();
        for (var parts : geometry.paths().values()) for (var part : parts) {
            TrackBlockVolume.create(part).forEach((pos, height) -> cells.merge(pos, height, Math::max));
        }
        return cells;
    }

    /** Includes automatic shared handles and isolated points, even while the draft is invalid. */
    public TrackCurveParameters automaticParameters(int endpoint) {
        return previewCurves.get(endpoint);
    }

    TrackVector stationDirection(int index) {
        return TotalStationRenderer.horizontalDirection(previewCurves.get(index).angle());
    }

    boolean isActive() {
        var player = Minecraft.getInstance().player;
        return draft != null && Minecraft.getInstance().level == world && player != null && player.isAlive()
                && ((player.getMainHandItem().getItem() instanceof TurnoutBuilderItem tool && tool.constructionType() == draft.kind())
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
    public void clear() {
        world = null; draft = null; geometry = null; volume = Map.of(); feedback = null;
        previewCurves = List.of();
    }
    TurnoutConstructionPayload draft() { return draft; }
    TrackAssemblyDefinition.Geometry geometry() { return geometry; }
    Map<BlockPos, Integer> volume() { return volume; }
    public Feedback previewFeedback() { return feedback; }
}
