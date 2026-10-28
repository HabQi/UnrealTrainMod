/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.network;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.client.gui.screen.TrainModelScreen;
import com.unrealtrainmod.client.gui.screen.RailModelScreen;
import com.unrealtrainmod.network.TrackSyncEvent;
import com.unrealtrainmod.network.payload.RailModelSelectionResultPayload;
import net.minecraft.world.InteractionHand;
import com.unrealtrainmod.network.ModelSelectionResultEvent;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

public final class ClientPayloadHandlers {
    private ClientPayloadHandlers() {}

    public static void openModelSelector(PlayerInteractEvent.RightClickItem event) {
        var minecraft = Minecraft.getInstance();
        if (event.getLevel().isClientSide && event.getEntity() == minecraft.player && minecraft.screen == null
                && event.getHand() == InteractionHand.MAIN_HAND && !event.getEntity().isShiftKeyDown()
                && event.getEntity().hasPermissions(2) && event.getItemStack().is(UnrealTrainMod.RAIL_PLACER.get())) {
            minecraft.setScreen(new RailModelScreen(event.getEntity().getInventory().selected, event.getItemStack(), minecraft.level.dimension()));
        }
        if (event.getLevel().isClientSide && event.getEntity() == minecraft.player && minecraft.screen == null
                && event.getItemStack().is(UnrealTrainMod.VEHICLE_PLACER.get())) {
            minecraft.setScreen(new TrainModelScreen(event.getHand(), event.getEntity().getInventory().selected, event.getItemStack()));
        }
    }

    public static void railSelectionResult(TrackSyncEvent event) {
        if (event.payload() instanceof RailModelSelectionResultPayload payload
                && Minecraft.getInstance().screen instanceof RailModelScreen screen) screen.acceptResult(payload);
    }

    public static void modelSelectionResult(ModelSelectionResultEvent event) {
        if (Minecraft.getInstance().screen instanceof TrainModelScreen screen) screen.acceptResult(event.payload());
    }
}
