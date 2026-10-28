/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.network.handler;

import com.unrealtrainmod.vehicle.VehicleCatalog;
import com.unrealtrainmod.track.model.RailCatalog;
import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.network.payload.SelectRailModelPayload;
import com.unrealtrainmod.network.payload.RailModelSelectionResultPayload;
import com.unrealtrainmod.network.payload.ModelSelectionResultPayload;
import com.unrealtrainmod.network.payload.SelectTrainModelPayload;
import com.unrealtrainmod.registry.ModDataComponents;
import com.unrealtrainmod.network.payload.SetTrackCurvePayload;
import com.unrealtrainmod.track.construction.TrackConstructionManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class ServerPayloadHandlers {
    private ServerPayloadHandlers() {}

    public static void setTrackCurve(SetTrackCurvePayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) TrackConstructionManager.setCurve(player, payload);
    }

    public static void selectRailModel(SelectRailModelPayload payload, IPayloadContext context) {
        var player = context.player();
        var stack = player.getMainHandItem();
        var result = ModelSelectionResultPayload.Result.SUCCESS;
        if (!player.isAlive() || !player.hasPermissions(2)) {
            result = ModelSelectionResultPayload.Result.NO_PERMISSION;
        } else if (payload.hotbarSlot() < 0 || payload.hotbarSlot() > 8
                || player.getInventory().selected != payload.hotbarSlot() || !stack.is(UnrealTrainMod.RAIL_PLACER.get())) {
            result = ModelSelectionResultPayload.Result.INVALID_ITEM;
        } else if (RailCatalog.find(payload.modelId()) == null) {
            result = ModelSelectionResultPayload.Result.UNKNOWN_MODEL;
        } else {
            stack.set(ModDataComponents.SELECTED_RAIL_MODEL.get(), payload.modelId());
            player.inventoryMenu.broadcastChanges();
        }
        context.reply(new RailModelSelectionResultPayload(payload.requestId(), result));
    }

    public static void selectTrainModel(SelectTrainModelPayload payload, IPayloadContext context) {
        var player = context.player();
        var stack = player.getItemInHand(payload.hand());
        var result = ModelSelectionResultPayload.Result.SUCCESS;
        if (!player.isAlive() || !player.mayBuild()) {
            result = ModelSelectionResultPayload.Result.NO_PERMISSION;
        } else if (payload.hotbarSlot() < 0 || payload.hotbarSlot() > 8
                || (payload.hand() == InteractionHand.MAIN_HAND && player.getInventory().selected != payload.hotbarSlot())
                || !stack.is(UnrealTrainMod.VEHICLE_PLACER.get())) {
            result = ModelSelectionResultPayload.Result.INVALID_ITEM;
        } else if (VehicleCatalog.find(payload.modelId()) == null) {
            result = ModelSelectionResultPayload.Result.UNKNOWN_MODEL;
        } else {
            stack.set(ModDataComponents.SELECTED_MODEL.get(), payload.modelId());
            player.inventoryMenu.broadcastChanges();
        }
        context.reply(new ModelSelectionResultPayload(payload.requestId(), result));
    }
}
