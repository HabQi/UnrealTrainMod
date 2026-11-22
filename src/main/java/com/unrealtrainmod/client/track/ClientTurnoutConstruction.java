/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.item.RailPlacerItem;
import com.unrealtrainmod.network.TrackSyncEvent;
import com.unrealtrainmod.network.payload.ConfirmTurnoutPayload;
import com.unrealtrainmod.network.payload.TurnoutConstructionPayload;
import com.unrealtrainmod.network.payload.TrackConstructionPayload.Feedback;
import com.unrealtrainmod.track.TrackCurveParameters;
import com.unrealtrainmod.track.TrackPlacementEndpoint;
import com.unrealtrainmod.track.AssemblyPlacementGeometry;
import com.unrealtrainmod.track.assembly.TrackAssemblyDefinition;
import com.unrealtrainmod.track.block.TrackBlockVolume;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.track.model.NativeTurnoutCatalog;
import com.unrealtrainmod.track.mesh.NativeDoubleSlipSolver;
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
            case READY, CREATED, CANCELLED, NATIVE_CROSSOVER_ADJUSTED -> true;
            default -> false;
        };
        var message = switch (payload.feedback()) {
            case READY -> Component.translatable("item.unrealtrainmod.turnout_builder.ready");
            case TURNOUT_POINTS -> Component.translatable(payload.feedback().translationKey(), payload.points().size());
            case TURNOUT_POINT_COUNT -> Component.translatable(payload.feedback().translationKey(),
                    payload.points().size(), payload.kind().pointCount());
            default -> Component.translatable(payload.feedback().translationKey());
        };
        if (payload.alternate()) {
            message.append(" · ").append(Component.translatable("item.unrealtrainmod.turnout_builder.alternate"));
        }
        mc.player.displayClientMessage(message
                .withStyle(inProgress(payload.feedback()) ? ChatFormatting.YELLOW
                        : success ? ChatFormatting.GREEN : ChatFormatting.RED), true);
    }

    static boolean inProgress(Feedback feedback) {
        return switch (feedback) {
            case START, TURNOUT_POINTS, TURNOUT_POINT_COUNT, TURNOUT_SOLVING, NATIVE_CROSSOVER_SOLVING -> true;
            default -> false;
        };
    }

    public boolean matches(ResourceLocation dimension, UUID revision) {
        return draft != null && Minecraft.getInstance().level == world
                && draft.dimension().equals(dimension) && draft.revision().equals(revision);
    }

    public void restorePreview() { if (draft != null) editPreview(-1, null, false); }

    public void editPreview(int endpoint, TrackCurveParameters curve, boolean edited) {
        if (draft == null) return;
        geometry = null; volume = Map.of(); feedback = draft.validation(); previewCurves = List.of();
        if (!draft.ordered()) return;
        var curves = new ArrayList<>(draft.curves());
        if (edited) curves.set(endpoint, curve);
        var points = draft.points().stream().map(TrackPlacementEndpoint::position).toList();
        previewCurves = AssemblyPlacementGeometry.defaults(draft.kind(), points, curves, 0);
        if (!edited && !draft.resolved()) return;
        try {
            geometry = AssemblyPlacementGeometry.create(draft.kind(), points, draft.gauge(), curves, 0);
            volume = footprint(geometry);
            if (edited) {
                AssemblyPlacementGeometry.validate(AssemblyPlacementGeometry.definition(draft.kind()), geometry);
                feedback = Feedback.START;
            }
        } catch (NativeDoubleSlipSolver.InvalidLayout exception) {
            if (edited || feedback == Feedback.READY) feedback = switch (exception.problem()) {
                case INTERSECTION -> Feedback.NATIVE_DOUBLE_SLIP_INTERSECTION;
                case ANGLE -> Feedback.NATIVE_DOUBLE_SLIP_ANGLE;
                case SPACE -> Feedback.NATIVE_DOUBLE_SLIP_SPACE;
            };
        } catch (IllegalArgumentException exception) {
            if (edited || feedback == Feedback.READY) feedback = Feedback.INVALID_GEOMETRY;
        }
    }

    private static Map<BlockPos, Integer> footprint(TrackAssemblyDefinition.Geometry geometry) {
        var cells = new HashMap<BlockPos, Integer>();
        for (var parts : geometry.paths().values()) for (var part : parts) {
            TrackBlockVolume.create(part).forEach((pos, height) -> cells.merge(pos, height, Math::max));
        }
        return cells;
    }

    /** Returns the current automatic or manual handle shown by the curve editor. */
    public TrackCurveParameters automaticParameters(int endpoint) {
        return previewCurves.get(endpoint);
    }

    TrackVector stationDirection(int index) {
        return TotalStationRenderer.horizontalDirection(draft.ordered() ? previewCurves.get(index).angle() : 0);
    }

    boolean isActive() {
        var player = Minecraft.getInstance().player;
        return draft != null && Minecraft.getInstance().level == world && player != null && player.isAlive()
                && (player.getMainHandItem().is(UnrealTrainMod.TURNOUT_BUILDER.get())
                || player.getMainHandItem().is(UnrealTrainMod.TRACK_WRENCH.get())
                || player.getMainHandItem().is(UnrealTrainMod.RAIL_PLACER.get()));
    }
    public void confirmPlacement(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock event) {
        if (!event.getLevel().isClientSide() || event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND
                || event.getEntity().isShiftKeyDown() || !event.getItemStack().is(UnrealTrainMod.RAIL_PLACER.get())
                || draft == null || event.getLevel() != world) return;
        event.setCanceled(true);
        event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new ConfirmTurnoutPayload(draft.dimension(), draft.revision(), event.getPos()));
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
    public Feedback previewFeedback() {
        var player = Minecraft.getInstance().player;
        if (geometry != null && player != null) {
            var selected = RailPlacerItem.constructionSelection(player);
            if (NativeTurnoutCatalog.contains(selected.modelId())
                    && ClientRailModelManager.INSTANCE.model(selected.modelId()) == null) return Feedback.MISSING_MODEL;
        }
        // Geometry edits remain pending until the server validates the applied parameters.
        return feedback;
    }
}
