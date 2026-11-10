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
import com.unrealtrainmod.track.TrackPlacementGeometry;
import com.unrealtrainmod.track.TurnoutPlacementGeometry;
import com.unrealtrainmod.track.DiamondCrossingGeometry;
import com.unrealtrainmod.track.SingleCrossoverGeometry;
import com.unrealtrainmod.track.construction.TrackConstructionType;
import com.unrealtrainmod.track.assembly.TrackAssemblyDefinition;
import com.unrealtrainmod.track.block.TrackBlockVolume;
import com.unrealtrainmod.track.geometry.BezierTrackGeometry;
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

/** Local reversible geometry; authoritative defaults include existing external-line tangents. */
public final class ClientTurnoutConstruction implements ResourceManagerReloadListener {
    public static final ClientTurnoutConstruction INSTANCE = new ClientTurnoutConstruction();
    private ClientLevel world;
    private TurnoutConstructionPayload draft;
    private TrackAssemblyDefinition.Geometry geometry;
    private Map<BlockPos, Integer> volume = Map.of();
    private Feedback feedback;
    private Map<TrackPlacementEndpoint, Float> selectionYaws = Map.of();
    private List<TrackCurveParameters> previewCurves = List.of();
    private ClientTurnoutConstruction() {}

    public void onSync(TrackSyncEvent event) {
        if (!(event.payload() instanceof TurnoutConstructionPayload payload)) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !mc.level.dimension().location().equals(payload.dimension())) return;
        var selectedYaws = new HashMap<TrackPlacementEndpoint, Float>();
        boolean sameDraft = draft != null && world == mc.level && draft.kind() == payload.kind()
                && !payload.points().isEmpty() && draft.points().getFirst().equals(payload.points().getFirst());
        for (var point : payload.points()) {
            selectedYaws.put(point, sameDraft ? selectionYaws.getOrDefault(point, mc.player.getYRot()) : mc.player.getYRot());
        }
        clear();
        if (!payload.points().isEmpty() && mc.player.isAlive()) {
            world = mc.level; draft = payload; selectionYaws = selectedYaws; restorePreview();
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
        if (draft.kind() == TrackConstructionType.DIAMOND_CROSSING
                || draft.kind() == TrackConstructionType.SINGLE_CROSSOVER) {
            try {
                geometry = draft.kind() == TrackConstructionType.DIAMOND_CROSSING
                        ? DiamondCrossingGeometry.create(draft.points().stream().map(p -> p.position()).toList(), draft.gauge())
                        : SingleCrossoverGeometry.create(draft.points().stream().map(p -> p.position()).toList(), draft.gauge());
                volume = footprint(geometry);
            } catch (IllegalArgumentException exception) { feedback = Feedback.INVALID_GEOMETRY; }
            return;
        }
        var curves = new ArrayList<TrackCurveParameters>();
        for (int i = 0; i < draft.points().size(); i++) {
            var raw = edited && endpoint == i ? curve : draft.curves().get(i);
            var automatic = draft.defaults().get(i);
            if (i > 0 && raw == null && !draft.locked().get(i)) {
                curves.add(null);
                continue;
            }
            double angle = raw == null || draft.locked().get(i) ? automatic.angle() : raw.angle();
            curves.add(new TrackCurveParameters(angle, raw == null ? automatic.length() : raw.length()));
        }
        previewCurves = curves;
        try {
            geometry = TurnoutPlacementGeometry.create(draft.points().get(0).position(),
                    draft.points().size() > 1 ? draft.points().get(1).position() : null,
                    draft.points().size() > 2 ? draft.points().get(2).position() : null, draft.gauge(),
                    curves.get(0), curves.size() > 1 ? curves.get(1) : null, curves.size() > 2 ? curves.get(2) : null,
                    curves.size() > 1 && draft.locked().get(1), curves.size() > 2 && draft.locked().get(2));
            volume = footprint(geometry);
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

    /** The free exit's editor values follow the current preview, including after restoring automatic. */
    public TrackCurveParameters automaticEndParameters(int endpoint) {
        if (geometry == null) return draft.defaults().get(endpoint);
        var path = (BezierTrackGeometry) geometry.paths().get(endpoint == 1 ? "straight" : "diverging").getFirst();
        var end = draft.points().get(endpoint).position();
        double dx = path.control2().x() - end.x(), dz = path.control2().z() - end.z();
        return new TrackCurveParameters(Math.toDegrees(Math.atan2(-dx, dz)),
                Math.clamp(Math.hypot(dx, dz), TrackCurveParameters.MIN_LENGTH, TrackCurveParameters.MAX_LENGTH));
    }

    TrackVector stationDirection(int index) {
        String name = switch (index) { case 0 -> "A"; case 1 -> "B"; case 2 -> "C"; default -> "D"; };
        var port = geometry == null ? null : geometry.ports().get(name);
        if (port != null) return TotalStationRenderer.horizontalDirection(port.outwardYaw() + 180);
        var point = draft.points().get(index);
        if (draft.kind() == TrackConstructionType.SIMPLE_TURNOUT) {
            var toe = previewCurves.getFirst();
            if (index == 0) return TotalStationRenderer.horizontalDirection(toe.angle());
            var end = point.position();
            var curve = previewCurves.get(index);
            boolean locked = draft.locked().get(index);
            var inward = locked ? new TrackCurveParameters(curve.angle() + 180, curve.length()) : curve;
            var control = TrackPlacementGeometry.control(draft.points().getFirst().position(), end,
                    toe, inward, false, true, locked);
            double dx = control.x() - end.x(), dz = control.z() - end.z();
            if (Math.hypot(dx, dz) > 0) return new TrackVector(dx, 0, dz);
        } else {
            int partner = index ^ 1;
            if (partner < draft.points().size()) {
                var from = point.position();
                var to = draft.points().get(partner).position();
                return new TrackVector(to.x() - from.x(), 0, to.z() - from.z());
            }
            if (draft.kind() == TrackConstructionType.SINGLE_CROSSOVER && index == 2) {
                var a = draft.points().get(0).position();
                var b = draft.points().get(1).position();
                return new TrackVector(b.x() - a.x(), 0, b.z() - a.z());
            }
        }
        return TotalStationRenderer.horizontalDirection(selectionYaws.get(point));
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
        selectionYaws = Map.of(); previewCurves = List.of();
    }
    TurnoutConstructionPayload draft() { return draft; }
    TrackAssemblyDefinition.Geometry geometry() { return geometry; }
    Map<BlockPos, Integer> volume() { return volume; }
    public Feedback previewFeedback() { return feedback; }
}
