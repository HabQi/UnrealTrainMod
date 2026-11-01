/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.train;

import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/** One ordinary passenger's server-side session. It never owns train controls. */
final class TrainPassengerSession {
    final ServerPlayer player;
    final UUID session;
    final UUID vehicle;
    double localX;
    double localZ;
    long acceptedSequence;
    long appliedSequence;
    int inputAge = Integer.MAX_VALUE;
    float inputX;
    float inputZ;
    /** Set while vanilla is still choosing the dismount position, so the personal local point remains readable. */
    boolean exiting;

    TrainPassengerSession(ServerPlayer player, UUID session, UUID vehicle, double localX, double localZ) {
        this.player = player;
        this.session = session;
        this.vehicle = vehicle;
        this.localX = localX;
        this.localZ = localZ;
    }
}
