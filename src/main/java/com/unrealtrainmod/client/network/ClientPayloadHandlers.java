/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.network;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.client.gui.screen.TrainModelScreen;
import com.unrealtrainmod.network.ModelSelectionResultEvent;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

public final class ClientPayloadHandlers {
    private ClientPayloadHandlers() {}

    public static void openModelSelector(PlayerInteractEvent.RightClickItem event) {
        var minecraft = Minecraft.getInstance();
        if (event.getLevel().isClientSide && event.getEntity() == minecraft.player && minecraft.screen == null
                && event.getItemStack().is(UnrealTrainMod.VEHICLE_PLACER.get())) {
            minecraft.setScreen(new TrainModelScreen(event.getHand(), event.getEntity().getInventory().selected, event.getItemStack()));
        }
    }

    public static void modelSelectionResult(ModelSelectionResultEvent event) {
        if (Minecraft.getInstance().screen instanceof TrainModelScreen screen) screen.acceptResult(event.payload());
    }
}
