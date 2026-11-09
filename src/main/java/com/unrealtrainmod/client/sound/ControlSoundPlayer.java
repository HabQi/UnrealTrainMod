/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.sound;

import com.unrealtrainmod.compat.ir.IrControlSound;
import com.unrealtrainmod.compat.ir.IrControlSoundState;
import com.unrealtrainmod.entity.TrainEntity;
import com.unrealtrainmod.resource.PackageAsset;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/** One original control component, with independent click, held-move and release voices. */
final class ControlSoundPlayer {
    private static final int DISTANCE = 10;
    private final IrControlSound definition;
    private final Voice engage = new Voice(), move = new Voice(), disengage = new Voice();
    private IrControlSoundState previous;
    private boolean audible;

    ControlSoundPlayer(IrControlSound definition) { this.definition = definition; }

    void update(TrainEntity entity, IrControlSoundState state, Vec3 listener, boolean enabled) {
        var old = previous;
        previous = state;
        boolean withinRange = enabled && PackageSoundInstance.position(entity, definition.position())
                .distanceToSqr(listener) < DISTANCE * DISTANCE;
        boolean resumed = old == null || !audible;
        audible = withinRange;
        if (!withinRange) {
            close();
            return;
        }
        if (!state.pressed() && state.equals(old) && !engage.active() && !move.active() && !disengage.active()) return;
        if (!resumed) {
            boolean pressed = state.presses() > old.presses();
            boolean released = state.releases() > old.releases();
            if (pressed) {
                disengage.close();
                engage.play(definition.engage(), false);
            }
            if (state.moves() > old.moves()) move.play(definition.move(), false);
            if (released) {
                // Atomic button clicks play their sample in full; an actual held control stops on release.
                if (!pressed) engage.close();
                disengage.play(definition.disengage(), false);
            }
        }
        if (state.pressed() && definition.movePercent() == null) {
            if (!move.active()) move.play(definition.move(), true);
        } else if (move.loop) move.close();
        engage.tick(entity);
        move.tick(entity);
        disengage.tick(entity);
    }

    void close() { engage.close(); move.close(); disengage.close(); }

    private final class Voice {
        private PackageSoundLibrary.Clip clip;
        private PackageSoundInstance voice;
        private boolean loop;
        private double remaining;

        boolean active() { return clip != null; }

        void play(PackageAsset asset, boolean loop) {
            close();
            if (asset == null) return;
            this.loop = loop;
            clip = PackageSoundLibrary.clip(asset);
        }

        void tick(TrainEntity entity) {
            if (clip == null) return;
            if (voice != null) {
                if (!loop && --remaining <= 0) close();
                return;
            }
            var decoded = clip.ready();
            if (decoded == null) return;
            voice = new PackageSoundInstance(entity, clip, loop, DISTANCE, 1, 1, definition.position());
            remaining = decoded.ticks();
            Minecraft.getInstance().getSoundManager().play(voice);
        }

        void close() {
            if (voice != null) voice.close();
            voice = null;
            clip = null;
            loop = false;
        }
    }
}
