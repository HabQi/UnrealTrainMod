/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.wiring;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.entity.ConnectorEntity;
import com.unrealtrainmod.registry.ModDataComponents;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class WiringManager {
    public static final double MAX_LENGTH = 256;
    private record Selection(ResourceLocation dimension, UUID first, ResourceLocation model, boolean removal) {}
    private record Removal(ResourceLocation dimension, UUID first, UUID second) {}
    private static final Map<UUID, Selection> SELECTIONS = new HashMap<>();
    private static final Map<UUID, Removal> REMOVALS = new HashMap<>();
    private WiringManager() {}

    public static void register(IEventBus bus) {
        bus.addListener(WiringManager::logout); bus.addListener(WiringManager::dimension);
        bus.addListener(WiringManager::clonePlayer); bus.addListener(WiringManager::stopped);
        bus.addListener(WiringManager::death);
        WiringSync.register(bus);
    }
    public static void place(ServerPlayer player, UseOnContext context) {
        if (!player.mayBuild() || !player.isAlive()) return;
        var model = context.getItemInHand().get(ModDataComponents.SELECTED_CONNECTOR_MODEL.get());
        var definition = WiringCatalog.find(model);
        if (definition == null || definition.wire()) { feedback(player, "unselected"); return; }
        var pos = context.getClickedPos(); var level = player.serverLevel();
        if (!level.getBlockState(pos).canBeReplaced()) pos = pos.relative(context.getClickedFace());
        if (!level.getBlockState(pos).canBeReplaced()) return;
        if (!player.mayUseItemAt(pos, context.getClickedFace(), context.getItemInHand())
                || level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos)) return;
        var node = new ConnectorData(UUID.randomUUID(), model, Vec3.atCenterOf(pos), context.getClickedFace(),
                Vec3.ZERO, Vec3.ZERO, 0);
        var entity = UnrealTrainMod.CONNECTOR.get().create(level);
        if (entity == null) return;
        entity.setUUID(node.id()); entity.apply(node);
        var graph = WiringSavedData.get(level); graph.put(node);
        if (!level.addFreshEntity(entity)) { graph.removeConnector(node.id()); return; }
        WiringSync.changed(level);
    }

    public static InteractionResult interact(ConnectorEntity entity, ServerPlayer player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND || !canReach(player, entity.node())) return InteractionResult.PASS;
        var stack = player.getMainHandItem(); var level = player.serverLevel(); var graph = WiringSavedData.get(level);
        var node = graph.connector(entity.getUUID()); if (node == null) return InteractionResult.PASS;
        if (stack.is(UnrealTrainMod.TRACK_WRENCH.get())) {
            PacketDistributor.sendToPlayer(player, new WiringPayloads.Message(UUID.randomUUID(), WiringPayloads.View.EDITOR,
                    true, "", node.save()));
        } else if (stack.is(UnrealTrainMod.INSULATOR.get()) && player.isShiftKeyDown()) {
            graph.removeConnector(node.id()); entity.discard(); cancel(player); WiringSync.changed(level);
        } else if (stack.is(UnrealTrainMod.WIRE.get())) {
            if (player.isShiftKeyDown()) { cancel(player); return InteractionResult.SUCCESS; }
            var model = stack.get(ModDataComponents.SELECTED_WIRE_MODEL.get());
            var definition = WiringCatalog.find(model);
            if (definition == null || !definition.wire()) { feedback(player, "unselected"); return InteractionResult.SUCCESS; }
            select(player, node, model, false);
        } else if (stack.isEmpty() && player.isShiftKeyDown()) {
            select(player, node, null, true);
        } else return InteractionResult.PASS;
        return InteractionResult.SUCCESS;
    }

    private static void select(ServerPlayer player, ConnectorData node, ResourceLocation model, boolean removal) {
        var key = player.getUUID(); var dimension = player.level().dimension().location();
        var selection = SELECTIONS.get(key);
        if (selection == null || selection.removal != removal || !selection.dimension.equals(dimension)) {
            SELECTIONS.put(key, new Selection(dimension, node.id(), model, removal));
            REMOVALS.remove(key); feedback(player, removal ? "remove_start" : "start"); return;
        }
        SELECTIONS.remove(key);
        if (selection.first.equals(node.id())) { feedback(player, "cancelled"); return; }
        var graph = WiringSavedData.get(player.serverLevel()); var first = graph.connector(selection.first);
        if (first == null) { feedback(player, "missing"); return; }
        if (removal) {
            var edges = new ListTag();
            graph.wires().stream().filter(w -> w.joins(first.id(), node.id())).forEach(w -> edges.add(w.save()));
            if (edges.isEmpty()) { feedback(player, "no_connections"); return; }
            REMOVALS.put(key, new Removal(dimension, first.id(), node.id()));
            var tag = new CompoundTag(); tag.putUUID("First", first.id()); tag.putUUID("Second", node.id()); tag.put("Wires", edges);
            PacketDistributor.sendToPlayer(player, new WiringPayloads.Message(UUID.randomUUID(), WiringPayloads.View.REMOVAL, true, "", tag));
            return;
        }
        double distance = first.wirePoint().distanceTo(node.wirePoint());
        if (distance < 1e-6 || distance > MAX_LENGTH) { feedback(player, "length"); return; }
        if (WiringCatalog.find(selection.model) == null) { feedback(player, "unselected"); return; }
        if (graph.wires().stream().anyMatch(w -> w.joins(first.id(), node.id()) && w.model().equals(selection.model))) {
            feedback(player, "duplicate"); return;
        }
        graph.put(new WireData(UUID.randomUUID(), first.id(), node.id(), selection.model));
        WiringSync.changed(player.serverLevel()); feedback(player, "connected");
    }

    public static void command(WiringPayloads.Command payload, IPayloadContext context) {
        var player = (ServerPlayer)context.player(); var tag = payload.data();
        boolean success = false; String message = "invalid";
        try {
            if (!player.mayBuild() || !player.isAlive()) throw new IllegalArgumentException("No build permission");
            switch (payload.action()) {
                case SELECT_CONNECTOR, SELECT_WIRE -> {
                    boolean wire = payload.action() == WiringPayloads.Action.SELECT_WIRE;
                    var stack = player.getMainHandItem();
                    if (!tag.contains("Slot", 3) || tag.getInt("Slot") != player.getInventory().selected
                            || !stack.is(wire ? UnrealTrainMod.WIRE.get() : UnrealTrainMod.INSULATOR.get())) break;
                    var id = ResourceLocation.parse(tag.getString("Model")); var definition = WiringCatalog.find(id);
                    if (definition == null || definition.wire() != wire) break;
                    stack.set(wire ? ModDataComponents.SELECTED_WIRE_MODEL.get() : ModDataComponents.SELECTED_CONNECTOR_MODEL.get(), id);
                    player.inventoryMenu.broadcastChanges(); cancelQuietly(player); success = true;
                }
                case EDIT -> {
                    var graph = WiringSavedData.get(player.serverLevel()); var node = graph.connector(tag.getUUID("Id"));
                    if (!player.getMainHandItem().is(UnrealTrainMod.TRACK_WRENCH.get()) || !canReach(player, node)) break;
                    if (!tag.contains("Revision", 3) || tag.getInt("Revision") != node.revision()) { message = "changed"; break; }
                    Vec3 offset = ConnectorData.vector(tag, "Offset"), raw = ConnectorData.vector(tag, "Angles");
                    var updated = node.edited(offset, new Vec3(raw.x % 360, raw.y % 360, raw.z % 360));
                    var pos = BlockPos.containing(updated.origin());
                    if (player.serverLevel().isOutsideBuildHeight(pos) || !player.serverLevel().getWorldBorder().isWithinBounds(pos)
                            || !player.mayUseItemAt(pos, node.face(), player.getMainHandItem())) break;
                    boolean valid = true;
                    for (var wire : graph.wires()) if (wire.touches(node.id())) {
                        var other = graph.connector(wire.first().equals(node.id()) ? wire.second() : wire.first());
                        double length = updated.wirePoint().distanceTo(other.wirePoint());
                        if (length < 1e-6 || length > MAX_LENGTH) { valid = false; message = "length"; break; }
                    }
                    if (!valid) break;
                    graph.put(updated);
                    if (player.serverLevel().getEntity(node.id()) instanceof ConnectorEntity e) e.apply(updated);
                    WiringSync.changed(player.serverLevel()); success = true;
                }
                case REMOVE_WIRE -> {
                    var removal = REMOVALS.get(player.getUUID());
                    if (removal == null || !removal.dimension.equals(player.level().dimension().location())
                            || !player.getMainHandItem().isEmpty()) break;
                    var graph = WiringSavedData.get(player.serverLevel()); var wire = graph.wire(tag.getUUID("Wire"));
                    if (wire == null || !wire.joins(removal.first, removal.second)
                            || !canReach(player, graph.connector(removal.second))) break;
                    graph.removeWire(wire.id()); WiringSync.changed(player.serverLevel()); REMOVALS.remove(player.getUUID()); success = true;
                }
                case CANCEL -> { cancel(player); success = true; }
            }
        } catch (IllegalArgumentException exception) { message = "invalid"; }
        context.reply(new WiringPayloads.Message(payload.requestId(), WiringPayloads.View.RESULT, success,
                success ? "success" : message, new CompoundTag()));
    }

    public static boolean canReach(ServerPlayer player, ConnectorData node) {
        if (node == null || !player.mayBuild() || !player.isAlive()) return false;
        double reach = player.entityInteractionRange() + 1;
        return node.bounds().distanceToSqr(player.getEyePosition()) <= reach * reach;
    }
    public static void cancel(ServerPlayer player) { cancelQuietly(player); feedback(player, "cancelled"); }
    private static void cancelQuietly(ServerPlayer player) { SELECTIONS.remove(player.getUUID()); REMOVALS.remove(player.getUUID()); }
    private static void feedback(ServerPlayer player, String key) {
        player.displayClientMessage(Component.translatable("wiring.unrealtrainmod." + key), true);
    }
    private static void logout(PlayerEvent.PlayerLoggedOutEvent e) { clear(e.getEntity().getUUID()); }
    private static void dimension(PlayerEvent.PlayerChangedDimensionEvent e) { clear(e.getEntity().getUUID()); }
    private static void clonePlayer(PlayerEvent.Clone e) { clear(e.getOriginal().getUUID()); }
    private static void death(net.neoforged.neoforge.event.entity.living.LivingDeathEvent e) {
        if (e.getEntity() instanceof ServerPlayer player) clear(player.getUUID());
    }
    private static void clear(UUID id) { SELECTIONS.remove(id); REMOVALS.remove(id); }
    private static void stopped(ServerStoppedEvent e) { SELECTIONS.clear(); REMOVALS.clear(); }
}
