/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.blueprint;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.blueprint.BlueprintPayloads;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;

public final class BlueprintClient {
    interface ResponseScreen { void accept(BlueprintPayloads.Message message); }
    private BlueprintClient() {}
    public static void register(IEventBus bus) {
        bus.addListener(BlueprintClient::message); bus.addListener(BlueprintClient::air);
    }
    static UUID command(BlueprintPayloads.Action action, CompoundTag data) {
        var id = UUID.randomUUID(); PacketDistributor.sendToServer(new BlueprintPayloads.Command(id, action, data)); return id;
    }
    private static void air(PlayerInteractEvent.RightClickItem e) {
        var mc = Minecraft.getInstance();
        if (!e.getLevel().isClientSide || e.getEntity() != mc.player || mc.screen != null || e.getHand() != InteractionHand.MAIN_HAND
                || e.getEntity().isShiftKeyDown() || !e.getEntity().mayBuild() || !e.getItemStack().is(UnrealTrainMod.BLUEPRINT.get())) return;
        mc.setScreen(new BlueprintEditorScreen());
    }
    private static void message(BlueprintPayloads.ClientEvent event) {
        var mc = Minecraft.getInstance(); if (mc.level == null || mc.player == null) return; var p = event.message();
        if (p.view() != BlueprintPayloads.View.RESULT) return;
        if (mc.screen instanceof ResponseScreen screen) screen.accept(p);
        else if (!p.key().isEmpty()) mc.player.displayClientMessage(feedback(p.key()), true);
    }
    static Component feedback(String key) {
        var parts = key.split("@", 2); var message = Component.translatable("blueprint.message." + parts[0]);
        return parts.length == 2 ? message.append(Component.translatable("blueprint.station_number", Integer.parseInt(parts[1]) + 1)) : message;
    }
}
