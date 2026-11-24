/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.blueprint;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.entity.ConnectorEntity;
import com.unrealtrainmod.item.BlueprintItem;
import com.unrealtrainmod.item.BlueprintPlacerItem;
import com.unrealtrainmod.registry.ModDataComponents;
import com.unrealtrainmod.track.TrackConnection;
import com.unrealtrainmod.track.TrackGraph;
import com.unrealtrainmod.track.TrackSegment;
import com.unrealtrainmod.track.block.TrackBlockManager;
import com.unrealtrainmod.track.persistence.TrackSavedData;
import com.unrealtrainmod.wiring.ConnectorData;
import com.unrealtrainmod.wiring.WireData;
import com.unrealtrainmod.wiring.WiringSavedData;
import com.unrealtrainmod.wiring.WiringSync;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.LoggerFactory;

/** Server-thread sessions, bounded construction batches and rollback of owned IDs only. */
public final class BlueprintManager {
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final Set<UUID> OWNED = new HashSet<>();
    private static final TicketType<UUID> TICKET = TicketType.create("unrealtrainmod_blueprint", UUID::compareTo);
    private static final double DEFAULT_SPACING = 32;

    private record RouteSegment(TrackSegment segment, UUID assemblyId, long assemblyRevision) {
        static RouteSegment capture(TrackGraph graph, TrackSegment segment) {
            var assembly = graph.assemblyForSegment(segment.id());
            return assembly == null ? new RouteSegment(segment, null, 0)
                    : new RouteSegment(segment, assembly.id(), assembly.revision());
        }

        boolean matches(TrackGraph graph) {
            if (graph.segment(segment.id()) != segment) return false;
            var assembly = graph.assemblyForSegment(segment.id());
            return assembly == null ? assemblyId == null
                    : assembly.id().equals(assemblyId) && assembly.revision() == assemblyRevision;
        }
    }

    private static final class Session {
        final ServerLevel level;
        final ServerPlayer player;
        final int slot;
        final UUID id = UUID.randomUUID();
        final ItemStack tool;
        final ItemContainerContents contents;
        final BlueprintDefinition definition;
        final int fingerprint;
        final List<List<BlueprintPaths.Anchor>> lines = new ArrayList<>();
        final Set<UUID> placed = new LinkedHashSet<>();
        final List<UUID> placedWires = new ArrayList<>();
        final Map<UUID, RouteSegment> routeSegments = new HashMap<>();
        final Map<UUID, List<TrackConnection>> junctions = new HashMap<>();
        int selected, completed;
        double spacing = DEFAULT_SPACING;
        boolean startB, reverse, building;
        UUID token;
        UUID lastBuild;
        BlueprintPlacementPlan plan;
        Session(ServerPlayer player, ItemStack blueprint) {
            this.player = player;
            level = player.serverLevel();
            slot = player.getInventory().selected;
            tool = player.getMainHandItem();
            contents = tool.get(DataComponents.CONTAINER);
            definition = BlueprintItem.definition(blueprint);
            fingerprint = definition.save().hashCode();
            definition.tracks().forEach(t -> lines.add(new ArrayList<>()));
        }
        boolean matches(ServerPlayer current) {
            return player == current && level == current.serverLevel() && current.isAlive() && current.mayBuild()
                    && current.getInventory().selected == slot && current.getMainHandItem() == tool
                    && tool.is(UnrealTrainMod.BLUEPRINT_PLACER.get()) && tool.get(DataComponents.CONTAINER) == contents;
        }
        void invalidate() {
            plan = null;
            token = null;
            routeSegments.clear();
            junctions.clear();
        }
    }
    private BlueprintManager() {}
    public static boolean underConstruction(UUID id) { return OWNED.contains(id); }
    public static void register(IEventBus bus) {
        bus.addListener(BlueprintManager::tick); bus.addListener(BlueprintManager::logout); bus.addListener(BlueprintManager::dimension);
        bus.addListener(BlueprintManager::clonePlayer); bus.addListener(BlueprintManager::stop);
    }
    private static Session session(ServerPlayer player, boolean create) {
        var s = SESSIONS.get(player.getUUID());
        if (s != null && !s.matches(player)) {
            cancelQuietly(player.getUUID());
            s = null;
        }
        if (s == null && create) {
            var blueprint = BlueprintPlacerItem.blueprint(player.getMainHandItem());
            if (blueprint.isEmpty()) throw new IllegalArgumentException("unloaded");
            s = new Session(player, blueprint);
            SESSIONS.put(player.getUUID(), s);
        }
        return s;
    }
    public static void pick(ServerPlayer player, UseOnContext context) {
        if (!canUsePlacer(player)) return;
        var request = UUID.randomUUID();
        Session s = null;
        try {
            s = session(player, true);
            if (s.building) throw new IllegalArgumentException("busy");
            var pos = context.getClickedPos();
            if (!s.level.getBlockState(pos).is(UnrealTrainMod.TRACK_BLOCK.get())) throw new IllegalArgumentException("no_track");
            double reach = player.blockInteractionRange() + 1;
            if (player.getEyePosition().distanceToSqr(context.getClickLocation()) > reach * reach) throw new IllegalArgumentException("reach");
            if (!s.level.mayInteract(player, pos) || !player.mayUseItemAt(pos, context.getClickedFace(), s.tool)) {
                throw new IllegalArgumentException("permission");
            }
            var tracks = TrackBlockManager.get(s.level);
            var anchor = BlueprintPaths.pick(tracks.segmentsAt(pos), context.getClickLocation());
            var line = s.lines.get(s.selected); if (line.size() >= 256) throw new IllegalArgumentException("limit");
            if (!line.isEmpty()) {
                var candidate = new ArrayList<>(line); candidate.add(anchor);
                BlueprintPaths.resolve(tracks.graph(), candidate, s.reverse);
            }
            line.add(anchor);
            s.invalidate();
            state(s, request, "selected");
        } catch (IllegalArgumentException e) {
            if (s == null) result(player, request, error(e));
            else state(s, request, error(e));
        }
    }

    private static boolean canUsePlacer(ServerPlayer player) {
        return player.isAlive() && player.mayBuild() && player.getMainHandItem().is(UnrealTrainMod.BLUEPRINT_PLACER.get());
    }

    public static void command(BlueprintPayloads.Command payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !player.isAlive() || !player.mayBuild()) return;
        var action = payload.action();
        var tag = payload.data();
        var request = payload.request();
        var stack = player.getMainHandItem();
        Session s = null;
        try {
            if (action == BlueprintPayloads.Action.SAVE) {
                if (!stack.is(UnrealTrainMod.BLUEPRINT.get())) return;
                if (player.getInventory().selected != tag.getInt("Slot")
                        || !BlueprintItem.definition(stack).save().equals(tag.getCompound("Original"))) throw new IllegalArgumentException("changed");
                var definition = BlueprintDefinition.load(tag.getCompound("Definition"));
                stack.set(ModDataComponents.BLUEPRINT.get(), definition);
                player.containerMenu.broadcastChanges();
                result(player, request, "saved");
                return;
            }
            if (!canUsePlacer(player)) return;
            if (!tag.contains("Slot", Tag.TAG_INT) || tag.getInt("Slot") != player.getInventory().selected) {
                throw new IllegalArgumentException("changed");
            }
            boolean status = action == BlueprintPayloads.Action.STATUS;
            var current = session(player, status);
            if (current == null || !status && (!tag.hasUUID("Session") || !current.id.equals(tag.getUUID("Session")))) {
                throw new IllegalArgumentException("changed");
            }
            s = current;
            if (s.building && !status && action != BlueprintPayloads.Action.CANCEL) throw new IllegalArgumentException("busy");
            switch (action) {
                case STATUS -> state(s, request, "");
                case CANCEL -> cancel(s, request, "cancelled");
                case SELECT_LINE, CLEAR_LINE, UNDO_POINT -> {
                    int line = tag.getInt("Line");
                    if (!tag.contains("Line", Tag.TAG_INT) || line < 0 || line >= s.lines.size()) {
                        throw new IllegalArgumentException("binding");
                    }
                    s.selected = line;
                    var anchors = s.lines.get(line);
                    if (action != BlueprintPayloads.Action.SELECT_LINE && !anchors.isEmpty()) {
                        if (action == BlueprintPayloads.Action.CLEAR_LINE) anchors.clear();
                        else anchors.removeLast();
                        s.invalidate();
                    }
                    state(s, request, "");
                }
                case OPTIONS -> {
                    options(s, tag);
                    state(s, request, "");
                }
                case PREVIEW -> {
                    options(s, tag);
                    prepare(s);
                    sendPlan(s, request);
                    state(s, request, "ready");
                }
                case BUILD -> build(s, request, tag);
                case SAVE -> throw new IllegalStateException("Blueprint save must be handled before placement");
            }
        } catch (IllegalArgumentException e) {
            if (s == null) result(player, request, error(e));
            else state(s, request, error(e));
        } catch (RuntimeException e) {
            LoggerFactory.getLogger(BlueprintManager.class).warn("Blueprint request failed", e);
            if (s == null) result(player, request, "storage");
            else cancel(s, request, "storage");
        }
    }

    private static void options(Session s, CompoundTag tag) {
        double spacing = tag.getDouble("Spacing");
        if (!Double.isFinite(spacing) || spacing < .01 || spacing > 256) throw new IllegalArgumentException("spacing");
        boolean startB = s.definition.alternating() && tag.getBoolean("StartB");
        boolean reverse = tag.getBoolean("Reverse");
        if (s.spacing != spacing || s.startB != startB || s.reverse != reverse) {
            s.spacing = spacing;
            s.startB = startB;
            s.reverse = reverse;
            s.invalidate();
        }
    }

    private static void build(Session s, UUID request, CompoundTag tag) {
        if (!tag.hasUUID("Token")) throw new IllegalArgumentException("changed");
        var token = tag.getUUID("Token");
        if (token.equals(s.lastBuild)) {
            state(s, request, "created");
            return;
        }
        if (!token.equals(s.token) || s.plan == null) throw new IllegalArgumentException("changed");
        try {
            if (changed(s, true)) throw new IllegalArgumentException("changed");
            s.definition.validate(true);
            validateWorld(s, s.plan);
        } catch (IllegalArgumentException e) {
            s.invalidate();
            throw e;
        }
        s.building = true;
        s.completed = 0;
        s.plan.stations().forEach(station -> station.nodes().forEach(n -> OWNED.add(n.id())));
        state(s, request, "building");
    }

    private static void prepare(Session s) {
        s.invalidate();
        var graph = TrackSavedData.get(s.level).graph();
        var paths = new ArrayList<BlueprintPaths.Path>();
        for (var line : s.lines) paths.add(BlueprintPaths.resolve(graph, line, s.reverse));
        var token = UUID.randomUUID();
        var plan = BlueprintPlacementPlan.generate(s.definition, paths, s.spacing, s.startB, token);
        validateWorld(s, plan);
        for (var path : paths) for (var id : path.segments()) {
            if (s.routeSegments.containsKey(id)) continue;
            var segment = graph.segment(id);
            s.routeSegments.put(id, RouteSegment.capture(graph, segment));
            s.junctions.computeIfAbsent(segment.startNodeId(), graph::connections);
            s.junctions.computeIfAbsent(segment.endNodeId(), graph::connections);
        }
        s.token = token;
        s.plan = plan;
    }

    private static boolean changed(Session s, boolean checkJunctions) {
        var graph = TrackSavedData.get(s.level).graph();
        try {
            for (var segment : s.routeSegments.values()) if (!segment.matches(graph)) return true;
            if (checkJunctions) for (var entry : s.junctions.entrySet()) {
                if (!graph.connections(entry.getKey()).equals(entry.getValue())) return true;
            }
        } catch (IllegalArgumentException e) {
            return true;
        }
        return false;
    }
    private static void validateWorld(Session s, BlueprintPlacementPlan plan) {
        for (var station : plan.stations()) validateStation(s, station);
    }
    private static void validateStation(Session s, BlueprintPlacementPlan.Station station) {
        var existing = WiringSavedData.get(s.level);
        for (var node : station.nodes()) {
            var pos = BlockPos.containing(node.origin());
            if (s.level.isOutsideBuildHeight(pos) || !s.level.getWorldBorder().isWithinBounds(pos)) throw new IllegalArgumentException("bounds@" + station.index());
            if (!s.level.mayInteract(s.player, pos) || !s.player.mayUseItemAt(pos, node.face(), s.tool)) throw new IllegalArgumentException("permission@" + station.index());
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
            if (changed(s, false)) throw new IllegalArgumentException("changed");
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
                s.building = false;
                s.lastBuild = s.token;
                s.placed.clear();
                s.placedWires.clear();
                s.plan.stations().forEach(station -> station.nodes().forEach(n -> OWNED.remove(n.id())));
                s.lines.forEach(List::clear);
                s.selected = 0;
                s.completed = 0;
                s.invalidate();
                state(s, UUID.randomUUID(), "created");
            } else state(s, UUID.randomUUID(), "building");
        } catch (IllegalArgumentException e) {
            cancel(s, UUID.randomUUID(), error(e));
        } catch (RuntimeException e) {
            LoggerFactory.getLogger(BlueprintManager.class).warn("Blueprint construction failed", e);
            cancel(s, UUID.randomUUID(), "storage");
        }
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
        if (!canUsePlacer(player)) return;
        var s = session(player, false);
        if (s != null) cancel(s, UUID.randomUUID(), "cancelled");
    }
    private static void cancel(Session s, UUID request, String key) {
        cancelQuietly(s.player.getUUID());
        var tag = sessionTag(s);
        tag.putBoolean("Closed", true);
        send(s.player, new BlueprintPayloads.Message(request, BlueprintPayloads.View.STATE, key, tag));
    }
    private static void cancelQuietly(UUID player) { var s = SESSIONS.remove(player); if (s != null) rollback(s); }
    private static CompoundTag sessionTag(Session s) {
        var tag = new CompoundTag();
        tag.putUUID("Session", s.id);
        tag.putInt("Slot", s.slot);
        tag.putInt("BlueprintHash", s.fingerprint);
        tag.putString("Dimension", s.level.dimension().location().toString());
        return tag;
    }
    private static void state(Session s, UUID request, String key) {
        var tag = sessionTag(s);
        tag.putIntArray("Points", s.lines.stream().mapToInt(List::size).toArray());
        tag.putInt("Line", s.selected);
        tag.putDouble("Spacing", s.spacing);
        tag.putBoolean("StartB", s.startB);
        tag.putBoolean("Reverse", s.reverse);
        tag.putBoolean("Building", s.building);
        tag.putInt("Completed", s.completed);
        if (s.plan != null) {
            tag.putUUID("Token", s.token);
            tag.putInt("Stations", s.plan.stations().size());
            if (!s.building) {
                tag.putDouble("Length", s.plan.length());
                tag.putInt("Nodes", s.plan.nodeCount());
                tag.putInt("Wires", s.plan.wireCount());
            }
        }
        send(s.player, new BlueprintPayloads.Message(request, BlueprintPayloads.View.STATE, key, tag));
    }
    private static void sendPlan(Session s, UUID request) {
        var nodes = new ArrayList<ConnectorData>(); var wires = new ArrayList<WireData>();
        s.plan.stations().forEach(station -> { nodes.addAll(station.nodes()); wires.addAll(station.wires()); });
        int count = Math.max(nodes.size(), wires.size());
        for (int start = 0; start < count; start += BlueprintPayloads.PLAN_BATCH) {
            var tag = sessionTag(s);
            tag.putUUID("Token", s.token);
            tag.putInt("Offset", start);
            int end = start + BlueprintPayloads.PLAN_BATCH;
            var ns = new ListTag(); var ws = new ListTag();
            for (int i = start; i < Math.min(end, nodes.size()); i++) ns.add(nodes.get(i).save());
            for (int i = start; i < Math.min(end, wires.size()); i++) ws.add(wires.get(i).save());
            tag.put("Nodes", ns);
            tag.put("Wires", ws);
            send(s.player, new BlueprintPayloads.Message(request, BlueprintPayloads.View.PLAN, "", tag));
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
