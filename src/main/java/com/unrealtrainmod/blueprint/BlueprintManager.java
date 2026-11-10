/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.blueprint;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.entity.ConnectorEntity;
import com.unrealtrainmod.item.BlueprintItem;
import com.unrealtrainmod.registry.ModDataComponents;
import com.unrealtrainmod.track.TrackSegment;
import com.unrealtrainmod.track.persistence.TrackSavedData;
import com.unrealtrainmod.wiring.ConnectorData;
import com.unrealtrainmod.wiring.WiringSavedData;
import com.unrealtrainmod.wiring.WiringSync;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server-thread sessions, bounded construction batches and rollback of owned IDs only. */
public final class BlueprintManager {
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final java.util.Set<UUID> OWNED = new HashSet<>();
    private static final TicketType<UUID> TICKET = TicketType.create("unrealtrainmod_blueprint", UUID::compareTo);
    private static final class Session {
        final ServerLevel level;
        final ServerPlayer player;
        final int slot;
        final BlueprintDefinition definition;
        final int fingerprint;
        final List<List<BlueprintPlacementRequest.Anchor>> lines = new ArrayList<>();
        final java.util.Set<UUID> placed = new java.util.LinkedHashSet<>();
        final List<UUID> placedWires = new ArrayList<>();
        final Map<UUID, TrackSegment> geometry = new HashMap<>();
        int selected, completed;
        double spacing = 32;
        boolean startB, reverse, building;
        UUID token = UUID.randomUUID();
        UUID lastBuild;
        BlueprintPlacementPlan plan;
        Session(ServerPlayer player) {
            this.player = player;
            level = player.serverLevel(); slot = player.getInventory().selected;
            definition = BlueprintItem.definition(player.getMainHandItem());
            fingerprint = definition.save().hashCode();
            definition.tracks().forEach(t -> lines.add(new ArrayList<>()));
        }
        boolean matches(ServerPlayer p) {
            return level == p.serverLevel() && p.isAlive() && p.mayBuild() && p.getInventory().selected == slot
                    && p.getMainHandItem().is(UnrealTrainMod.BLUEPRINT.get()) && definition.equals(BlueprintItem.definition(p.getMainHandItem()));
        }
        void invalidate() { plan = null; token = UUID.randomUUID(); geometry.clear(); }
        BlueprintPlacementRequest request() { return new BlueprintPlacementRequest(lines, spacing, startB, reverse); }
    }
    private BlueprintManager() {}
    public static boolean underConstruction(UUID id) { return OWNED.contains(id); }
    public static void register(IEventBus bus) {
        bus.addListener(BlueprintManager::tick); bus.addListener(BlueprintManager::logout); bus.addListener(BlueprintManager::dimension);
        bus.addListener(BlueprintManager::clonePlayer); bus.addListener(BlueprintManager::stop);
    }
    private static Session session(ServerPlayer p) {
        var s = SESSIONS.get(p.getUUID());
        if (s != null && !s.matches(p)) { rollback(s); SESSIONS.remove(p.getUUID()); s = null; }
        if (s == null) { s = new Session(p); SESSIONS.put(p.getUUID(), s); }
        return s;
    }
    public static void pick(ServerPlayer player, UseOnContext context) {
        if (!player.mayBuild()) return;
        var id = UUID.randomUUID();
        try {
            var s = session(player); if (s.building) return;
            double reach = player.blockInteractionRange() + 1;
            if (player.getEyePosition().distanceToSqr(context.getClickLocation()) > reach * reach) throw new IllegalArgumentException("reach");
            var anchor = BlueprintPaths.pick(TrackSavedData.get(s.level).graph(), context.getClickLocation());
            var line = s.lines.get(s.selected); if (line.size() >= 256) throw new IllegalArgumentException("limit");
            if (!line.isEmpty()) {
                var candidate = new ArrayList<>(line); candidate.add(anchor);
                BlueprintPaths.resolve(TrackSavedData.get(s.level).graph(), candidate, false);
            }
            line.add(anchor); s.invalidate(); state(player, s, id, "selected");
        } catch (IllegalArgumentException e) { result(player, id, error(e)); }
    }
    public static void command(BlueprintPayloads.Command payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        if (!player.isAlive() || !player.mayBuild() || !player.getMainHandItem().is(UnrealTrainMod.BLUEPRINT.get())) return;
        var tag = payload.data(); var id = payload.request();
        try {
            if (payload.action() == BlueprintPayloads.Action.CANCEL) { cancel(player); return; }
            if (payload.action() == BlueprintPayloads.Action.SAVE) {
                if (player.getInventory().selected != tag.getInt("Slot")
                        || !BlueprintItem.definition(player.getMainHandItem()).save().equals(tag.getCompound("Original"))) throw new IllegalArgumentException("changed");
                var definition = BlueprintDefinition.load(tag.getCompound("Definition"));
                cancelQuietly(player.getUUID()); player.getMainHandItem().set(ModDataComponents.BLUEPRINT.get(), definition);
                player.inventoryMenu.broadcastChanges();
                result(player, id, "saved"); return;
            }
            var s = session(player);
            if (s.building && payload.action() != BlueprintPayloads.Action.STATUS) { result(player, id, "busy"); return; }
            switch (payload.action()) {
                case STATUS -> state(player, s, id, "");
                case SELECT_LINE, CLEAR_LINE, UNDO_POINT -> {
                    int line = tag.getInt("Line"); if (line < 0 || line >= s.lines.size()) throw new IllegalArgumentException("binding");
                    s.selected = line;
                    if (payload.action() == BlueprintPayloads.Action.CLEAR_LINE) s.lines.get(line).clear();
                    if (payload.action() == BlueprintPayloads.Action.UNDO_POINT && !s.lines.get(line).isEmpty()) s.lines.get(line).removeLast();
                    s.invalidate(); state(player, s, id, "");
                }
                case OPTIONS -> {
                    double spacing = tag.getDouble("Spacing");
                    if (!Double.isFinite(spacing) || spacing < .01 || spacing > 256) throw new IllegalArgumentException("spacing");
                    s.spacing = spacing; s.startB = tag.getBoolean("StartB"); s.reverse = tag.getBoolean("Reverse");
                    s.invalidate(); state(player, s, id, "");
                }
                case PREVIEW -> { prepare(s); sendPlan(player, s, id); state(player, s, id, "ready"); }
                case BUILD -> {
                    UUID token = tag.getUUID("Token");
                    if (token.equals(s.lastBuild)) { state(player, s, id, "created"); break; }
                    if (!token.equals(s.token) || s.plan == null || changed(s)) throw new IllegalArgumentException("changed");
                    validateWorld(s); s.building = true; s.completed = 0;
                    s.plan.stations().forEach(station -> station.nodes().forEach(n -> OWNED.add(n.id())));
                    state(player, s, id, "building");
                }
                default -> { }
            }
        } catch (IllegalArgumentException e) { result(player, id, error(e)); }
        catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(BlueprintManager.class).warn("Blueprint request failed", e);
            cancelQuietly(player.getUUID()); result(player, id, "storage");
        }
    }
    private static void prepare(Session s) {
        s.definition.validate(true); var graph = TrackSavedData.get(s.level).graph(); var paths = new ArrayList<BlueprintPaths.Path>();
        for (var line : s.lines) paths.add(BlueprintPaths.resolve(graph, line, s.reverse));
        s.invalidate();
        // Capture all graph objects used by the selected finite routes; immutable identity detects edits/removals.
        for (var path : paths) for (var id : path.segments()) s.geometry.put(id, graph.segment(id));
        s.plan = BlueprintPlacementPlan.generate(s.definition, paths, s.spacing, s.startB, s.token);
        validateWorld(s);
    }
    private static boolean changed(Session s) {
        var graph = TrackSavedData.get(s.level).graph();
        for (var entry : s.geometry.entrySet()) {
            try { if (graph.segment(entry.getKey()) != entry.getValue()) return true; }
            catch (IllegalArgumentException e) { return true; }
        }
        return false;
    }
    private static void validateWorld(Session s) {
        for (var station : s.plan.stations()) validateStation(s, station);
    }
    private static void validateStation(Session s, BlueprintPlacementPlan.Station station) {
        var existing = WiringSavedData.get(s.level);
        for (var node : station.nodes()) {
            var pos = BlockPos.containing(node.origin());
            if (s.level.isOutsideBuildHeight(pos) || !s.level.getWorldBorder().isWithinBounds(pos)) throw new IllegalArgumentException("bounds@" + station.index());
            if (!s.level.mayInteract(s.player, pos) || !s.player.mayUseItemAt(pos, node.face(), s.player.getMainHandItem())) throw new IllegalArgumentException("permission@" + station.index());
            for (var near : existing.nearOrigin(node.origin())) if (!s.placed.contains(near.id()) && near.model().equals(node.model())
                    && near.origin().distanceToSqr(node.origin()) < .0001) throw new IllegalArgumentException("overlap@" + station.index());
        }
    }
    private static void tick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var s = SESSIONS.get(player.getUUID()); if (s == null) return;
        if (!s.matches(player)) { cancelQuietly(player.getUUID()); return; }
        if (!s.building) return;
        try {
            if (changed(s)) throw new IllegalArgumentException("changed");
            var graph = WiringSavedData.get(s.level); int budget = 0;
            while (s.completed < s.plan.stations().size() && budget < 128) {
                var station = s.plan.stations().get(s.completed); var tickets = new HashSet<ChunkPos>();
                try {
                    validateStation(s, station);
                    for (var node : station.nodes()) tickets.add(new ChunkPos(BlockPos.containing(node.origin())));
                    for (var chunk : tickets) { s.level.getChunkSource().addRegionTicket(TICKET, chunk, 2, player.getUUID()); s.level.getChunk(chunk.x, chunk.z); }
                    for (var node : station.nodes()) {
                        graph.put(node); s.placed.add(node.id());
                        var entity = new ConnectorEntity(UnrealTrainMod.CONNECTOR.get(), s.level); entity.setUUID(node.id()); entity.apply(node);
                        if (!s.level.addFreshEntity(entity)) throw new IllegalArgumentException("entity");
                    }
                    for (var wire : station.wires()) { graph.put(wire); s.placedWires.add(wire.id()); }
                    budget += station.nodes().size() + station.wires().size(); s.completed++;
                } finally { for (var chunk : tickets) s.level.getChunkSource().removeRegionTicket(TICKET, chunk, 2, player.getUUID()); }
            }
            WiringSync.changed(s.level);
            if (s.completed == s.plan.stations().size()) {
                s.building = false; s.lastBuild = s.token; s.placed.clear(); s.placedWires.clear();
                s.plan.stations().forEach(station -> station.nodes().forEach(n -> OWNED.remove(n.id())));
                state(player, s, UUID.randomUUID(), "created"); s.plan = null;
            } else state(player, s, UUID.randomUUID(), "building");
        } catch (RuntimeException e) { cancelQuietly(player.getUUID()); result(player, UUID.randomUUID(), error(e)); }
    }
    private static void rollback(Session s) {
        if (s.plan != null) s.plan.stations().forEach(station -> station.nodes().forEach(n -> OWNED.remove(n.id())));
        if (s.placed.isEmpty() && s.placedWires.isEmpty()) return;
        var graph = WiringSavedData.get(s.level);
        for (var id : s.placedWires) graph.removeWire(id);
        for (var id : s.placed) { graph.removeConnector(id); var entity = s.level.getEntity(id); if (entity != null) entity.discard(); }
        s.placed.clear(); s.placedWires.clear(); WiringSync.changed(s.level);
    }
    public static void cancel(ServerPlayer player) {
        cancelQuietly(player.getUUID()); result(player, UUID.randomUUID(), "cancelled");
    }
    private static void cancelQuietly(UUID player) { var s = SESSIONS.remove(player); if (s != null) rollback(s); }
    private static void state(ServerPlayer player, Session s, UUID id, String key) {
        var tag = s.request().save(); tag.putInt("Line", s.selected); tag.putUUID("Token", s.token);
        tag.putInt("Slot", s.slot); tag.putInt("BlueprintHash", s.fingerprint);
        tag.putString("Dimension", s.level.dimension().location().toString()); tag.putBoolean("Building", s.building);
        tag.putInt("Completed", s.completed);
        if (s.plan != null) { tag.putDouble("Length", s.plan.length()); tag.putInt("Stations", s.plan.stations().size()); tag.putInt("Nodes", s.plan.nodeCount()); tag.putInt("Wires", s.plan.wireCount()); }
        send(player, new BlueprintPayloads.Message(id, BlueprintPayloads.View.STATE, key, tag));
    }
    private static void sendPlan(ServerPlayer player, Session s, UUID request) {
        var nodes = new ArrayList<ConnectorData>(); var wires = new ArrayList<com.unrealtrainmod.wiring.WireData>();
        s.plan.stations().forEach(station -> { nodes.addAll(station.nodes()); wires.addAll(station.wires()); });
        int count = Math.max(nodes.size(), wires.size());
        for (int start = 0; start < Math.max(1, count); start += 64) {
            var tag = new CompoundTag(); tag.putUUID("Token", s.token); tag.putString("Dimension", s.level.dimension().location().toString());
            tag.putBoolean("Reset", start == 0); tag.putBoolean("Complete", start + 64 >= count);
            var ns = new ListTag(); var ws = new ListTag();
            for (int i = start; i < Math.min(start + 64, nodes.size()); i++) ns.add(nodes.get(i).save());
            for (int i = start; i < Math.min(start + 64, wires.size()); i++) ws.add(wires.get(i).save());
            tag.put("Nodes", ns); tag.put("Wires", ws); send(player, new BlueprintPayloads.Message(request, BlueprintPayloads.View.PLAN, "", tag));
        }
    }
    private static void result(ServerPlayer player, UUID id, String key) { send(player, new BlueprintPayloads.Message(id, BlueprintPayloads.View.RESULT, key, new CompoundTag())); }
    private static void send(ServerPlayer player, BlueprintPayloads.Message message) { PacketDistributor.sendToPlayer(player, message); }
    private static String error(Exception e) {
        String key = e.getMessage(); return key != null && key.matches("[a-z_]+(@[0-9]+)?") ? key : "path";
    }
    private static void logout(PlayerEvent.PlayerLoggedOutEvent e) { cancelQuietly(e.getEntity().getUUID()); }
    private static void dimension(PlayerEvent.PlayerChangedDimensionEvent e) { cancelQuietly(e.getEntity().getUUID()); }
    private static void clonePlayer(PlayerEvent.Clone e) { cancelQuietly(e.getOriginal().getUUID()); }
    private static void stop(ServerStoppingEvent e) { SESSIONS.values().forEach(BlueprintManager::rollback); SESSIONS.clear(); OWNED.clear(); }
}
