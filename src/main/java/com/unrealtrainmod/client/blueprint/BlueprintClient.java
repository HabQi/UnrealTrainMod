/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.blueprint;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.blueprint.BlueprintPayloads;
import com.unrealtrainmod.blueprint.BlueprintPayloads.Action;
import com.unrealtrainmod.client.compat.iris.IrisCompat;
import com.unrealtrainmod.client.wiring.WiringRenderCache;
import com.unrealtrainmod.item.BlueprintItem;
import com.unrealtrainmod.item.BlueprintPlacerItem;
import com.unrealtrainmod.wiring.ConnectorData;
import com.unrealtrainmod.wiring.WireData;
import java.util.ArrayList;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.network.PacketDistributor;

public final class BlueprintClient {
    interface ResponseScreen { void accept(BlueprintPayloads.Message message); }

    private static final BlueprintScene SCENE = new BlueprintScene();
    private static ItemStack tool = ItemStack.EMPTY;
    private static ItemContainerContents contents;
    private static ItemStack blueprint = ItemStack.EMPTY;
    private static int slot = -1, fingerprint;
    private static CompoundTag state;
    private static UUID statusRequest, previewRequest, renderedToken;
    private static PreviewPackets previewPackets;

    private static final class PreviewPackets {
        final UUID token;
        final ArrayList<ConnectorData> nodes = new ArrayList<>();
        final ArrayList<WireData> wires = new ArrayList<>();
        int offset;

        PreviewPackets(UUID token) { this.token = token; }

        boolean add(CompoundTag data) {
            if (!token.equals(data.getUUID("Token")) || data.getInt("Offset") != offset) return false;
            for (var tag : data.getList("Nodes", Tag.TAG_COMPOUND)) nodes.add(ConnectorData.load((CompoundTag) tag));
            for (var tag : data.getList("Wires", Tag.TAG_COMPOUND)) wires.add(WireData.load((CompoundTag) tag));
            offset += BlueprintPayloads.PLAN_BATCH;
            return true;
        }
    }

    private BlueprintClient() {}
    public static void register(IEventBus bus) {
        bus.addListener(BlueprintClient::message);
        bus.addListener(BlueprintClient::air);
        bus.addListener(BlueprintClient::render);
        bus.addListener(BlueprintClient::tick);
        bus.addListener(BlueprintClient::unload);
        bus.addListener(BlueprintClient::logout);
    }
    static UUID command(Action action, CompoundTag data) {
        var id = UUID.randomUUID();
        PacketDistributor.sendToServer(new BlueprintPayloads.Command(id, action, data));
        return id;
    }
    static UUID placementCommand(Action action, CompoundTag data) {
        observeTool();
        data.putInt("Slot", slot);
        if (action != Action.STATUS) data.putUUID("Session", state.getUUID("Session"));
        var request = command(action, data);
        if (action == Action.STATUS) statusRequest = request;
        if (action == Action.PREVIEW) {
            clearScene();
            previewPackets = null;
            previewRequest = request;
        }
        return request;
    }
    static ItemStack blueprint() { observeTool(); return blueprint; }
    static CompoundTag state() { return state; }
    static boolean ready() {
        return state != null && !state.getBoolean("Building") && renderedToken != null && state.hasUUID("Token")
                && renderedToken.equals(state.getUUID("Token"));
    }
    private static void air(PlayerInteractEvent.RightClickItem e) {
        var mc = Minecraft.getInstance();
        if (!e.getLevel().isClientSide || e.getEntity() != mc.player || mc.screen != null || e.getHand() != InteractionHand.MAIN_HAND
                || e.getEntity().isShiftKeyDown() || !e.getEntity().mayBuild()) return;
        if (e.getItemStack().is(UnrealTrainMod.BLUEPRINT.get())) {
            mc.setScreen(new BlueprintEditorScreen());
        } else if (e.getItemStack().is(UnrealTrainMod.BLUEPRINT_PLACER.get())) {
            var loaded = blueprint();
            if (loaded.isEmpty()) {
                mc.player.displayClientMessage(feedback("unloaded"), true);
                return;
            }
            var screen = new BlueprintPlacementScreen(loaded);
            mc.setScreen(screen);
            screen.refresh();
        }
    }
    private static void message(BlueprintPayloads.ClientEvent event) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        var message = event.message();
        if (message.view() == BlueprintPayloads.View.RESULT) {
            if (mc.screen instanceof ResponseScreen editor) {
                editor.accept(message);
            } else if (mc.screen instanceof BlueprintPlacementScreen screen && screen.isWaitingFor(message.request())) {
                if (message.request().equals(statusRequest)) statusRequest = null;
                screen.acceptResult(message);
            } else if (mc.screen == null && !message.key().isEmpty()) {
                mc.player.displayClientMessage(feedback(message.key()), true);
            }
            return;
        }

        observeTool();
        var data = message.data();
        if (blueprint.isEmpty() || !data.hasUUID("Session") || data.getInt("Slot") != slot || data.getInt("BlueprintHash") != fingerprint
                || !data.getString("Dimension").equals(mc.level.dimension().location().toString())) return;
        UUID session = data.getUUID("Session");
        UUID active = state == null ? null : state.getUUID("Session");
        if (message.view() == BlueprintPayloads.View.PLAN) {
            if (session.equals(active) && message.request().equals(previewRequest)) collect(data);
            return;
        }

        boolean status = message.request().equals(statusRequest);
        if (status) statusRequest = null;
        if (active != null && !session.equals(active)) {
            if (!status) return;
            clearSession();
        }
        if (data.getBoolean("Closed")) {
            if (!session.equals(active)) return;
            clearSession();
            if (mc.screen instanceof BlueprintPlacementScreen screen) screen.onClose();
        } else {
            state = data;
            if (!ready()) clearScene();
            if (message.request().equals(previewRequest)) {
                var packets = previewPackets;
                previewRequest = null;
                previewPackets = null;
                if (message.key().equals("ready")) {
                    if (packets != null && data.hasUUID("Token") && packets.token.equals(data.getUUID("Token"))
                            && packets.nodes.size() == data.getInt("Nodes") && packets.wires.size() == data.getInt("Wires")) {
                        SCENE.set(packets.nodes, packets.wires);
                        renderedToken = packets.token;
                    } else {
                        message = new BlueprintPayloads.Message(message.request(), message.view(), "preview_incomplete", data);
                    }
                }
            }
            if (mc.screen instanceof BlueprintPlacementScreen screen) screen.acceptState(message);
        }

        if (mc.screen == null && !message.key().isEmpty()) {
            Component info = message.key().equals("building")
                    ? Component.translatable("blueprint.placer.progress", data.getInt("Completed"), data.getInt("Stations"))
                    : feedback(message.key());
            mc.player.displayClientMessage(info, true);
        }
    }

    private static void collect(CompoundTag data) {
        if (data.getInt("Offset") == 0) previewPackets = new PreviewPackets(data.getUUID("Token"));
        if (previewPackets != null && !previewPackets.add(data)) previewPackets = null;
    }

    private static void observeTool() {
        var player = Minecraft.getInstance().player;
        var current = player != null && player.isAlive() && player.mayBuild() ? player.getMainHandItem() : ItemStack.EMPTY;
        if (!current.is(UnrealTrainMod.BLUEPRINT_PLACER.get())) current = ItemStack.EMPTY;
        var loaded = current.get(DataComponents.CONTAINER);
        int selected = current.isEmpty() ? -1 : player.getInventory().selected;
        if (current == tool && loaded == contents && selected == slot) return;
        clearSession();
        tool = current;
        contents = loaded;
        slot = selected;
        blueprint = current.isEmpty() ? ItemStack.EMPTY : BlueprintPlacerItem.blueprint(current);
        fingerprint = blueprint.isEmpty() ? 0 : BlueprintItem.definition(blueprint).save().hashCode();
    }

    private static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || renderedToken == null || IrisCompat.shadowPass()) return;
        observeTool();
        if (!ready()) return;
        var shader = RenderSystem.getShader();
        var color = RenderSystem.getShaderColor().clone();
        var modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.set(event.getModelViewMatrix());
        RenderSystem.applyModelViewMatrix();
        try (var bytes = new ByteBufferBuilder(65536)) {
            RenderSystem.setShaderColor(1, 1, 1, 1);
            var buffers = MultiBufferSource.immediate(bytes);
            SCENE.render(new PoseStack(), buffers, false, event.getCamera().getPosition(), event.getFrustum(),
                    event.getPartialTick().getGameTimeDeltaPartialTick(false));
            WiringRenderCache.flushWorld();
            buffers.endBatch();
        } finally {
            RenderSystem.setShader(() -> shader);
            RenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }

    private static void clearScene() {
        if (renderedToken != null) SCENE.close();
        renderedToken = null;
    }
    static void clearSession() {
        clearScene();
        state = null;
        statusRequest = null;
        previewRequest = null;
        previewPackets = null;
    }
    private static void clear() {
        clearSession();
        tool = ItemStack.EMPTY;
        contents = null;
        blueprint = ItemStack.EMPTY;
        slot = -1;
    }
    private static void tick(ClientTickEvent.Post event) { observeTool(); }
    private static void unload(LevelEvent.Unload event) { if (event.getLevel().isClientSide()) clear(); }
    private static void logout(ClientPlayerNetworkEvent.LoggingOut event) { clear(); }

    static Component feedback(String key) {
        var parts = key.split("@", 2);
        var message = Component.translatable("blueprint.message." + parts[0]);
        return parts.length == 2 ? message.append(Component.translatable("blueprint.station_number", Integer.parseInt(parts[1]) + 1)) : message;
    }
}
