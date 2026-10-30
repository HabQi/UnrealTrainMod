/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.train;

import com.unrealtrainmod.entity.TrainEntity;
import com.unrealtrainmod.network.payload.TrainDebugPayload;
import com.unrealtrainmod.track.block.TrackBlockManager;
import com.unrealtrainmod.vehicle.VehicleCatalog;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** An opt-in, read-only server snapshot; observing a train never restores or starts it. */
public final class TrainDiagnostics {
    private static final String PREFIX = "gui.unrealtrainmod.train_debug.";
    private record Target(ServerLevel level, UUID entity) {}
    private static final Map<ServerPlayer, Target> WATCHERS = new IdentityHashMap<>();
    private TrainDiagnostics() {}

    public static void register(IEventBus bus) {
        bus.addListener(TrainDiagnostics::tick);
        bus.addListener(TrainDiagnostics::logout);
        bus.addListener(TrainDiagnostics::dimensionChanged);
        bus.addListener(TrainDiagnostics::cloned);
        bus.addListener(TrainDiagnostics::unload);
        bus.addListener(TrainDiagnostics::serverStopped);
    }

    public static void watch(ServerPlayer player, TrainEntity entity) {
        WATCHERS.put(player, new Target(player.serverLevel(), entity.getUUID()));
        send(player, lines(player.serverLevel(), entity));
    }

    public static void stop(ServerPlayer player) {
        WATCHERS.remove(player);
        send(player, List.of());
    }

    private static void send(ServerPlayer player, List<Component> lines) {
        PacketDistributor.sendToPlayer(player, new TrainDebugPayload(player.level().dimension().location(), lines));
    }

    private static void tick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getServer().getTickCount() % 5 != 0) return;
        for (var entry : List.copyOf(WATCHERS.entrySet())) {
            var player = entry.getKey();
            var target = entry.getValue();
            if (target.level() != level) continue;
            if (player.serverLevel() != level || !player.isAlive() || !player.hasPermissions(2)) {
                stop(player);
                continue;
            }
            if (level.getEntity(target.entity()) instanceof TrainEntity entity) {
                send(player, lines(level, entity));
            } else send(player, List.of(Component.translatable(PREFIX + "missing", target.entity().toString())));
        }
    }

    public static List<Component> lines(ServerLevel level, TrainEntity entity) {
        var lines = new ArrayList<Component>();
        lines.add(Component.translatable(PREFIX + "vehicle", entity.getUUID().toString()));
        try {
            var manager = TrainManager.get(level);
            var train = manager.knownTrain(entity.getUUID());
            if (train == null) return List.of(Component.translatable(PREFIX + "unavailable"));
            var occupancy = manager.occupancy();
            lines.add(Component.translatable(PREFIX + "train", train.id().toString(), train.cars().size()));
            var state = Component.translatable("commands.unrealtrainmod.train.state."
                    + (train.running() ? "running" : train.stopReason().name().toLowerCase(Locale.ROOT)));
            lines.add(Component.translatable(PREFIX + "state", state, manager.isRestored(train)));
            lines.add(Component.translatable(PREFIX + "speed", number(train.configuredSpeed()), number(train.actualSpeed())));
            var dynamics = train.dynamics();
            lines.add(Component.translatable(PREFIX + "forces", number(dynamics.tractionForce() / 1000),
                    number(dynamics.brakeForce() / 1000), number(dynamics.tractionPower() / 1000)));
            for (var car : train.cars()) {
                var brake = dynamics.brake(car.entityId());
                lines.add(brake == null ? Component.translatable(PREFIX + "brake_unavailable", car.entityId().toString())
                        : Component.translatable(PREFIX + "brake_member", car.entityId().toString(),
                                number(brake.targetForce(train.control()) / 1000), number(brake.actualForce() / 1000)));
            }
            lines.add(Component.translatable(PREFIX + "window", number(train.progress()),
                    number(train.route() == null ? 0 : train.route().getLength()), train.routeDirty(),
                    train.window() != null && train.window().refreshRequested()));
            var member = train.member(entity.getUUID());
            var anchor = member.anchor();
            if (anchor != null) lines.add(Component.translatable(PREFIX + "anchor", anchor.segmentId().toString(),
                    number(anchor.distance()), anchor.fromStart()));
            var model = VehicleCatalog.find(member.modelId());
            if (model != null && train.route() != null && !train.routeDirty()) {
                double reference = train.progress() + member.offset();
                lines.add(Component.translatable(PREFIX + "bogies", number(reference + model.motionSpec().frontBogieOffset()),
                        number(reference + model.motionSpec().rearBogieOffset())));
            }
            var intervals = occupancy.intervals(train.id());
            lines.addAll(occupancyLines(train, occupancy, 6, 2));
            TrackBlockManager.get(level).graph().assemblies().stream().filter(assembly ->
                    train.cars().stream().anyMatch(car -> car.entityId().equals(assembly.occupant()))
                            || intervals.stream().anyMatch(interval -> assembly.segments().contains(interval.segmentId())))
                    .limit(3).forEach(assembly -> lines.add(Component.translatable(PREFIX + "turnout", assembly.id().toString(),
                            assembly.occupant() == null ? "-" : assembly.occupant().toString())));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            lines.add(Component.translatable(PREFIX + "unavailable"));
        }
        return List.copyOf(lines);
    }

    public static List<Component> occupancyLines(TrainConsist train, TrackOccupancyManager occupancy,
                                                int intervalLimit, int conflictLimit) {
        var lines = new ArrayList<Component>();
        var intervals = occupancy.intervals(train.id());
        var conflicts = occupancy.conflicts(train.id());
        lines.add(Component.translatable(PREFIX + "occupancy", intervals.size(), conflicts.size()));
        intervals.stream().limit(intervalLimit).forEach(interval -> lines.add(Component.translatable(PREFIX + "interval",
                interval.segmentId().toString(), number(interval.start()), number(interval.end()))));
        conflicts.stream().limit(conflictLimit).forEach(conflict -> lines.add(Component.translatable(PREFIX + "conflict",
                conflict.trainId().toString(), conflict.segmentId().toString(), number(conflict.start()), number(conflict.end()))));
        if (intervals.size() > intervalLimit || conflicts.size() > conflictLimit) {
            lines.add(Component.translatable(PREFIX + "limited", intervalLimit, conflictLimit));
        }
        return List.copyOf(lines);
    }

    private static String number(double value) { return String.format(Locale.ROOT, "%.3f", value); }
    private static void logout(PlayerEvent.PlayerLoggedOutEvent event) { WATCHERS.remove(event.getEntity()); }
    private static void dimensionChanged(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) stop(player);
    }
    private static void cloned(PlayerEvent.Clone event) { WATCHERS.remove(event.getOriginal()); }
    private static void unload(LevelEvent.Unload event) { WATCHERS.values().removeIf(target -> target.level() == event.getLevel()); }
    private static void serverStopped(ServerStoppedEvent event) { WATCHERS.clear(); }
}
