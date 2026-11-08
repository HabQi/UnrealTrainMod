/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.sound;

import com.unrealtrainmod.compat.ir.IrVehicleDefinition;
import com.unrealtrainmod.compat.ir.IrVehicleSnapshot;
import com.unrealtrainmod.compat.ir.IrVehicleSounds;
import com.unrealtrainmod.entity.TrainEntity;
import com.unrealtrainmod.vehicle.VehicleCatalog;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/** World voices are driven by tracked entities, independent of render visibility and model loading. */
public final class VehicleSoundManager implements ResourceManagerReloadListener {
    public static final VehicleSoundManager INSTANCE = new VehicleSoundManager();
    private final Map<Integer, Controller> vehicles = new HashMap<>();
    private Level level;
    private long generation = PackageSoundLibrary.generation();

    private VehicleSoundManager() {}

    public void onTick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (level != mc.level || generation != PackageSoundLibrary.generation()) {
            clear();
            level = mc.level;
            generation = PackageSoundLibrary.generation();
        }
        if (mc.level == null || mc.isPaused()) return;
        var iterator = vehicles.values().iterator();
        while (iterator.hasNext()) {
            var controller = iterator.next();
            var entity = controller.entity;
            var model = VehicleCatalog.find(entity.getModelId());
            if (entity.isRemoved() || mc.level.getEntity(entity.getId()) != entity
                    || model == null || model.ir() != controller.definition) {
                controller.close();
                iterator.remove();
            }
        }
        boolean enabled = mc.options.getSoundSourceVolume(SoundSource.MASTER) > 0
                && mc.options.getSoundSourceVolume(SoundSource.NEUTRAL) > 0;
        Vec3 listener = mc.gameRenderer.getMainCamera().getPosition();
        for (var tracked : mc.level.entitiesForRendering()) {
            if (!(tracked instanceof TrainEntity entity) || entity.isRemoved()) continue;
            var model = VehicleCatalog.find(entity.getModelId());
            if (model == null) continue;
            // Establish the initial edge baseline from tracked state, not the pre-spawn placeholder.
            if (entity.irSnapshot() == IrVehicleSnapshot.EMPTY) continue;
            var controller = vehicles.get(entity.getId());
            if (controller == null) {
                if (!hasSounds(model.ir())) continue;
                controller = new Controller(entity, model.ir());
                vehicles.put(entity.getId(), controller);
            }
            controller.tick(listener, enabled);
        }
    }

    private static boolean hasSounds(IrVehicleDefinition definition) {
        var sounds = definition.sounds();
        return sounds.wheels() != null || sounds.idle() != null || sounds.running() != null || sounds.horn() != null
                || definition.animations().stream().anyMatch(animation -> animation.sound() != null);
    }

    public void onUnload(LevelEvent.Unload event) {
        if (event.getLevel() == level) clear();
    }

    public void onLogout(ClientPlayerNetworkEvent.LoggingOut event) { clear(); }

    @Override public void onResourceManagerReload(ResourceManager manager) { clear(); }

    public void clear() {
        vehicles.values().forEach(Controller::close);
        vehicles.clear();
        level = null;
    }

    private static final class Controller {
        private final TrainEntity entity;
        private final IrVehicleDefinition definition;
        private final IrVehicleSounds sounds;
        private final SoundSequencePlayer wheels, idle, running, horn;
        private final SoundSequencePlayer[] animations;
        private float working;

        Controller(TrainEntity entity, IrVehicleDefinition definition) {
            this.entity = entity;
            this.definition = definition;
            sounds = definition.sounds();
            wheels = new SoundSequencePlayer(sounds.wheels());
            idle = new SoundSequencePlayer(sounds.idle());
            running = new SoundSequencePlayer(sounds.running());
            horn = new SoundSequencePlayer(sounds.horn());
            animations = definition.animations().stream().map(animation -> new SoundSequencePlayer(animation.sound()))
                    .toArray(SoundSequencePlayer[]::new);
        }

        void tick(Vec3 listener, boolean enabled) {
            var pose = entity.renderPose(1);
            double distanceSquared = pose == null ? entity.position().distanceToSqr(listener)
                    : listener.distanceToSqr(pose.body().position().x(), pose.body().position().y(), pose.body().position().z());
            var state = entity.runtimeState();
            var snapshot = entity.irSnapshot();
            double speed = Math.abs(state.speed());
            boolean engine = active(sounds.engineGroups());
            float throttle = (float)state.throttle();
            working += Math.clamp((engine ? throttle : 0) - working, -0.05f, 0.05f);
            wheels.update(entity, speed >= 0.05, (float)Math.clamp(speed / (30 / 3.6), 0, 1),
                    (float)(0.8 + 0.4 * Math.clamp(speed / (120 / 3.6), 0, 1)), distanceSquared, enabled);
            idle.update(entity, engine, sounds.running() == null ? 1 : 1 - working, 1, distanceSquared, enabled);
            running.update(entity, engine, working, 0.8f + 0.4f * throttle, distanceSquared, enabled);
            horn.update(entity, active(sounds.hornGroups()), 1, 1, distanceSquared, enabled);
            for (int i = 0; i < animations.length; i++) {
                // Use the same authoritative edge clocks as the visual animation when available.
                boolean active = i < snapshot.clocks().size() ? snapshot.clocks().get(i).active()
                        : definition.animations().get(i).value(definition, snapshot.values(), state,
                                snapshot.frontCoupled(), snapshot.rearCoupled()) >= 0.95f;
                animations[i].update(entity, active, 1, 1, distanceSquared, enabled);
            }
        }

        private boolean active(Set<String> groups) {
            var snapshot = entity.irSnapshot();
            for (String group : groups) {
                if (definition.value(group, snapshot.values(), entity.runtimeState(),
                        snapshot.frontCoupled(), snapshot.rearCoupled()) >= 0.5f) return true;
            }
            return false;
        }

        void close() {
            wheels.close(); idle.close(); running.close(); horn.close();
            for (var animation : animations) animation.close();
        }
    }
}
