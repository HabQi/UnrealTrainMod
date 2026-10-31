/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.train.map;

import com.unrealtrainmod.network.payload.TrainMapSessionPayload;
import com.unrealtrainmod.network.payload.TrainMapUpdatePayload;
import com.unrealtrainmod.track.TrackGraph;
import com.unrealtrainmod.track.TrackSegmentChange;
import com.unrealtrainmod.track.TrackSegmentData;
import com.unrealtrainmod.track.assembly.TrackAssemblyInstance;
import com.unrealtrainmod.track.assembly.TrackTransitionResolver;
import com.unrealtrainmod.track.block.TrackBlockManager;
import com.unrealtrainmod.train.TrainManager;
import com.unrealtrainmod.train.map.TrainMapData.Entry;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.minecraft.commands.Commands.literal;

/** Server-thread subscriptions. Opening the monitor never prepares a train or acquires a turnout. */
public final class TrainMapService {
    private static final Logger LOGGER = LoggerFactory.getLogger(TrainMapService.class);
    private static final Map<ServerLevel, DimensionView> VIEWS = new IdentityHashMap<>();
    private record Watcher(ServerPlayer player, UUID session) {}
    private TrainMapService() {}

    public static void register(IEventBus bus) {
        bus.addListener(TrainMapService::commands);
        bus.addListener(TrainMapService::tick);
        bus.addListener(TrainMapService::logout);
        bus.addListener(TrainMapService::dimensionChanged);
        bus.addListener(TrainMapService::cloned);
        bus.addListener(TrainMapService::unload);
        bus.addListener(TrainMapService::serverStopped);
    }

    private static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(literal("utm").requires(source -> source.hasPermission(2))
                .then(literal("map").executes(context -> {
                    open(context.getSource().getPlayerOrException());
                    return 1;
                })));
    }

    private static void open(ServerPlayer player) {
        if (!player.isAlive() || !player.hasPermissions(2)) return;
        remove(player.getUUID(), null);
        var watcher = new Watcher(player, UUID.randomUUID());
        session(watcher, TrainMapSessionPayload.Status.OPEN);
        try {
            var view = VIEWS.computeIfAbsent(player.serverLevel(), DimensionView::new);
            view.watchers.put(player.getUUID(), watcher);
            view.send(watcher, TrainMapUpdatePayload.Channel.TOPOLOGY, true,
                    view.tracks.values().stream().map(value -> (Entry)new TrainMapData.Track(value)).toList());
            view.send(watcher, TrainMapUpdatePayload.Channel.DYNAMIC, true, view.dynamic());
        } catch (IllegalStateException | IllegalArgumentException exception) {
            remove(player.getUUID(), watcher.session());
            session(watcher, TrainMapSessionPayload.Status.ERROR);
            LOGGER.error("Cannot open train map in {}", player.level().dimension().location(), exception);
        }
    }

    public static void handleSession(TrainMapSessionPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player && payload.status() == TrainMapSessionPayload.Status.CLOSE
                && payload.dimension().equals(player.level().dimension().location())) remove(player.getUUID(), payload.session());
    }

    private static void session(Watcher watcher, TrainMapSessionPayload.Status status) {
        PacketDistributor.sendToPlayer(watcher.player(), new TrainMapSessionPayload(watcher.session(),
                watcher.player().level().dimension().location(), status));
    }

    private static void remove(UUID player, UUID session) {
        for (var view : List.copyOf(VIEWS.values())) {
            var watcher = view.watchers.get(player);
            if (watcher != null && (session == null || session.equals(watcher.session()))) view.watchers.remove(player);
            if (view.watchers.isEmpty()) {
                view.close();
                VIEWS.remove(view.level);
            }
        }
    }

    private static void tick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        var view = VIEWS.get(level);
        if (view == null) return;
        for (var watcher : List.copyOf(view.watchers.values())) {
            var player = watcher.player();
            if (player.serverLevel() != level || !player.isAlive() || !player.hasPermissions(2)) {
                session(watcher, player.hasPermissions(2) ? TrainMapSessionPayload.Status.CLOSE : TrainMapSessionPayload.Status.NO_PERMISSION);
                view.watchers.remove(player.getUUID());
            }
        }
        if (view.watchers.isEmpty()) {
            view.close(); VIEWS.remove(level); return;
        }
        if (level.getServer().getTickCount() % 5 != 0) return;
        try {
            if (!view.pending.isEmpty()) {
                var changes = List.copyOf(view.pending.values());
                view.sendAll(TrainMapUpdatePayload.Channel.TOPOLOGY, false, changes);
                view.pending.clear();
            }
            view.cachedDynamic = null;
            view.sendAll(TrainMapUpdatePayload.Channel.DYNAMIC, true, view.dynamic());
        } catch (IllegalStateException | IllegalArgumentException exception) {
            for (var watcher : view.watchers.values()) session(watcher, TrainMapSessionPayload.Status.ERROR);
            LOGGER.error("Cannot refresh train map in {}", level.dimension().location(), exception);
            view.close(); VIEWS.remove(level);
        }
    }

    private static void logout(PlayerEvent.PlayerLoggedOutEvent event) { remove(event.getEntity().getUUID(), null); }
    private static void dimensionChanged(PlayerEvent.PlayerChangedDimensionEvent event) { remove(event.getEntity().getUUID(), null); }
    private static void cloned(PlayerEvent.Clone event) { remove(event.getOriginal().getUUID(), null); }
    private static void unload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            var view = VIEWS.remove(level);
            if (view != null) view.close();
        }
    }
    private static void serverStopped(ServerStoppedEvent event) {
        for (var view : VIEWS.values()) view.close();
        VIEWS.clear();
    }

    private static final class DimensionView {
        private final ServerLevel level;
        private final TrackGraph graph;
        private final Map<UUID, Watcher> watchers = new HashMap<>();
        private final Map<UUID, TrackSegmentData> tracks = new LinkedHashMap<>();
        private final Map<UUID, Entry> pending = new LinkedHashMap<>();
        private final Consumer<TrackSegmentChange> trackListener = this::trackChanged;
        private final Consumer<TrackAssemblyInstance> assemblyListener = value -> cachedDynamic = null;
        private List<Entry> cachedDynamic;
        private long sequence;

        DimensionView(ServerLevel level) {
            this.level = level;
            graph = TrackBlockManager.get(level).graph();
            for (var segment : graph.segments()) tracks.put(segment.id(), TrackSegmentData.from(graph, segment));
            graph.addSegmentListener(trackListener);
            graph.addAssemblyListener(assemblyListener);
            graph.addAssemblyRemoveListener(assemblyListener);
        }

        private void trackChanged(TrackSegmentChange change) {
            var id = change.segment().id();
            if (change.type() == TrackSegmentChange.Type.REMOVED) {
                tracks.remove(id);
                pending.put(id, new TrainMapData.Removed(id));
            } else {
                var data = TrackSegmentData.from(graph, change.segment());
                tracks.put(id, data);
                pending.put(id, new TrainMapData.Track(data));
            }
            cachedDynamic = null;
        }

        private List<Entry> dynamic() {
            if (cachedDynamic != null) return cachedDynamic;
            var entries = new ArrayList<>(TrainManager.get(level).monitoringSnapshot(graph));
            for (var assembly : graph.assemblies()) {
                String controls = assembly.state().controls().entrySet().stream().sorted(Map.Entry.comparingByKey())
                        .map(entry -> entry.getKey() + "=" + entry.getValue().name()).collect(Collectors.joining(", "));
                entries.add(new TrainMapData.Turnout(assembly.id(), assembly.origin(), controls, assembly.occupant()));
                for (UUID segment : assembly.segments()) entries.add(new TrainMapData.Path(segment,
                        TrackTransitionResolver.isSegmentEnabled(graph, segment)));
            }
            cachedDynamic = List.copyOf(entries);
            return cachedDynamic;
        }

        private List<List<Entry>> batches(List<Entry> entries) {
            var result = new ArrayList<List<Entry>>();
            var batch = new ArrayList<Entry>();
            int bytes = 0;
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
            try {
                var wireEntries = new ArrayList<Entry>();
                for (var entry : entries) {
                    if (entry instanceof TrainMapData.Train train) fragment(train, buffer, wireEntries);
                    else wireEntries.add(entry);
                }
                for (var entry : wireEntries) {
                    buffer.clear();
                    TrainMapUpdatePayload.writeEntry(buffer, entry);
                    int size = buffer.readableBytes();
                    if (size > TrainMapUpdatePayload.MAX_BYTES) throw new IllegalArgumentException("Map entry exceeds packet budget");
                    if (batch.size() == TrainMapUpdatePayload.MAX_ENTRIES || bytes + size > TrainMapUpdatePayload.MAX_BYTES) {
                        result.add(List.copyOf(batch)); batch.clear(); bytes = 0;
                    }
                    batch.add(entry); bytes += size;
                }
                if (!batch.isEmpty() || result.isEmpty()) result.add(List.copyOf(batch));
            } finally { buffer.release(); }
            return result;
        }

        private void fragment(TrainMapData.Train train, RegistryFriendlyByteBuf buffer, List<Entry> result) {
            int total = train.cars().size();
            int first = 0;
            while (first < total) {
                buffer.clear();
                // Reserve the largest possible member-count varint; each car is measured only once per slice.
                TrainMapUpdatePayload.writeTrainHeader(buffer, train, total, first, Integer.MAX_VALUE);
                int bytes = buffer.readableBytes();
                int end = first;
                while (end < total) {
                    buffer.clear();
                    TrainMapUpdatePayload.writeCar(buffer, train.cars().get(end));
                    int size = buffer.readableBytes();
                    if (bytes + size > TrainMapUpdatePayload.MAX_BYTES) break;
                    bytes += size;
                    end++;
                }
                if (end == first) throw new IllegalArgumentException("Map car exceeds packet budget");
                var slice = new TrainMapData.Train(train.id(), train.running(), train.reason(), train.restored(),
                        train.configuredSpeed(), train.actualSpeed(), train.cars().subList(first, end));
                result.add(new TrainMapData.TrainFragment(slice, total, first));
                first = end;
            }
        }

        private void send(Watcher watcher, TrainMapUpdatePayload.Channel channel, boolean reset, List<Entry> entries) {
            var batches = batches(entries);
            sendBatches(watcher, channel, ++sequence, reset, batches);
        }

        private void sendAll(TrainMapUpdatePayload.Channel channel, boolean reset, List<Entry> entries) {
            var batches = batches(entries);
            long version = ++sequence;
            for (var watcher : watchers.values()) sendBatches(watcher, channel, version, reset, batches);
        }

        private void sendBatches(Watcher watcher, TrainMapUpdatePayload.Channel channel, long version,
                                 boolean reset, List<List<Entry>> batches) {
            for (int index = 0; index < batches.size(); index++) PacketDistributor.sendToPlayer(watcher.player(),
                    new TrainMapUpdatePayload(watcher.session(), level.dimension().location(), channel,
                            version, reset, index, batches.size(), batches.get(index)));
        }

        private void close() {
            graph.removeSegmentListener(trackListener);
            graph.removeAssemblyListener(assemblyListener);
            graph.removeAssemblyRemoveListener(assemblyListener);
            watchers.clear(); tracks.clear(); pending.clear(); cachedDynamic = null;
        }
    }
}
