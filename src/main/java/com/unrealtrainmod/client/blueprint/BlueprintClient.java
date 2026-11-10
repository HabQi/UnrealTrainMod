/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.blueprint;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.blueprint.BlueprintPayloads;
import com.unrealtrainmod.client.compat.iris.IrisCompat;
import com.unrealtrainmod.wiring.ConnectorData;
import com.unrealtrainmod.wiring.WireData;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.network.PacketDistributor;

public final class BlueprintClient {
    interface ResponseScreen { void accept(BlueprintPayloads.Message message); }
    static CompoundTag state = new CompoundTag();
    private static final BlueprintScene PREVIEW = new BlueprintScene();
    private static BlueprintScene pending;
    private static UUID token;
    private static com.unrealtrainmod.blueprint.BlueprintDefinition observedDefinition;
    private static int observedFingerprint;
    private BlueprintClient() {}
    public static void register(IEventBus bus) {
        bus.addListener(BlueprintClient::message); bus.addListener(BlueprintClient::air); bus.addListener(BlueprintClient::render);
        bus.addListener(BlueprintClient::unload); bus.addListener(BlueprintClient::logout);
        bus.addListener(BlueprintClient::tick);
    }
    static UUID command(BlueprintPayloads.Action action, CompoundTag data) {
        if (action != BlueprintPayloads.Action.BUILD && action != BlueprintPayloads.Action.STATUS && action != BlueprintPayloads.Action.SAVE) clearPreview();
        var id = UUID.randomUUID(); PacketDistributor.sendToServer(new BlueprintPayloads.Command(id, action, data)); return id;
    }
    private static void air(PlayerInteractEvent.RightClickItem e) {
        var mc = Minecraft.getInstance();
        if (!e.getLevel().isClientSide || e.getEntity() != mc.player || mc.screen != null || e.getHand() != InteractionHand.MAIN_HAND
                || e.getEntity().isShiftKeyDown() || !e.getEntity().mayBuild() || !e.getItemStack().is(UnrealTrainMod.BLUEPRINT.get())) return;
        mc.setScreen(new BlueprintEditorScreen());
    }
    private static void message(BlueprintPayloads.ClientEvent event) {
        var mc = Minecraft.getInstance(); if (mc.level == null || mc.player == null) return; var p = event.message(); var data = p.data();
        if (p.view() == BlueprintPayloads.View.PLAN) {
            if (!mc.level.dimension().location().toString().equals(data.getString("Dimension"))) return;
            if (data.getBoolean("Reset")) { clearPreview(); pending = new BlueprintScene(); token = data.getUUID("Token"); }
            if (pending == null || !data.getUUID("Token").equals(token)) return;
            for (var t : data.getList("Nodes", 10)) { var n = ConnectorData.load((CompoundTag)t); pending.nodes.put(n.id(), n); }
            for (var t : data.getList("Wires", 10)) { var w = WireData.load((CompoundTag)t); pending.wires.put(w.id(), w); }
            if (data.getBoolean("Complete")) { PREVIEW.set(java.util.List.copyOf(pending.nodes.values()), java.util.List.copyOf(pending.wires.values())); pending.close(); pending = null; }
            return;
        }
        if (p.view() == BlueprintPayloads.View.STATE) {
            if (!mc.level.dimension().location().toString().equals(data.getString("Dimension"))) return;
            state = data.copy();
            if (token != null && !token.equals(data.getUUID("Token")) || data.getBoolean("Building") || p.key().equals("created")) clearPreview();
            if (mc.screen == null && !p.key().equals("building") && !p.key().equals("created")) mc.setScreen(new BlueprintPlacementScreen());
        }
        if (p.key().equals("cancelled") || p.key().equals("saved")) clear();
        if (mc.screen instanceof ResponseScreen screen) screen.accept(p);
        else if (!p.key().isEmpty()) mc.player.displayClientMessage(feedback(p.key()), true);
    }
    static Component feedback(String key) {
        var parts = key.split("@", 2); var message = Component.translatable("blueprint.message." + parts[0]);
        return parts.length == 2 ? message.append(Component.translatable("blueprint.station_number", Integer.parseInt(parts[1]) + 1)) : message;
    }
    private static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || PREVIEW.nodes.isEmpty() || IrisCompat.shadowPass()) return;
        var mc = Minecraft.getInstance(); if (mc.player == null || !mc.player.getMainHandItem().is(UnrealTrainMod.BLUEPRINT.get())) return;
        var stack = RenderSystem.getModelViewStack(); stack.pushMatrix(); stack.set(event.getModelViewMatrix()); RenderSystem.applyModelViewMatrix();
        try (var bytes = new ByteBufferBuilder(65536)) {
            var buffers = MultiBufferSource.immediate(bytes);
            PREVIEW.render(new PoseStack(), buffers, false, event.getCamera().getPosition(), event.getFrustum(), event.getPartialTick().getGameTimeDeltaPartialTick(false));
            com.unrealtrainmod.client.wiring.WiringRenderCache.flushWorld();
            buffers.endBatch();
        } finally { stack.popMatrix(); RenderSystem.applyModelViewMatrix(); }
    }
    static boolean ready() { return token != null && state.hasUUID("Token") && token.equals(state.getUUID("Token")) && pending == null && !PREVIEW.nodes.isEmpty(); }
    private static void clearPreview() { PREVIEW.close(); if (pending != null) pending.close(); pending = null; token = null; }
    public static void clear() { clearPreview(); state = new CompoundTag(); observedDefinition = null; }
    private static boolean matchesDefinition(net.minecraft.world.item.ItemStack stack) {
        var definition = com.unrealtrainmod.item.BlueprintItem.definition(stack);
        if (definition != observedDefinition) { observedDefinition = definition; observedFingerprint = definition.save().hashCode(); }
        return observedFingerprint == state.getInt("BlueprintHash");
    }
    private static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post e) {
        var mc = Minecraft.getInstance();
        if (!state.isEmpty() && (mc.player == null || mc.level == null || !mc.player.isAlive()
                || !mc.level.dimension().location().toString().equals(state.getString("Dimension"))
                || mc.player.getInventory().selected != state.getInt("Slot")
                || !mc.player.getMainHandItem().is(UnrealTrainMod.BLUEPRINT.get())
                || !matchesDefinition(mc.player.getMainHandItem()))) clear();
    }
    private static void unload(LevelEvent.Unload e) { if (e.getLevel().isClientSide()) clear(); }
    private static void logout(ClientPlayerNetworkEvent.LoggingOut e) { clear(); }
}
