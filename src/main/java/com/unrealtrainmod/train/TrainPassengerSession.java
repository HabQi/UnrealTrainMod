/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.train;

import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/** One rider's walking session. Driver controls belong to a separate session. */
final class TrainPassengerSession {
    final ServerPlayer player;
    final UUID session;
    final UUID vehicle;
    final TrainWalkRole role;
    double localX;
    final double floorY;
    double localZ;
    long acceptedSequence;
    long appliedSequence;
    int inputAge = Integer.MAX_VALUE;
    float inputX;
    float inputZ;
    PassengerPose pose = PassengerPose.STANDING;
    int heightStep;
    /** Set while vanilla is still choosing the dismount position, so the personal local point remains readable. */
    boolean exiting;

    TrainPassengerSession(ServerPlayer player, UUID session, UUID vehicle, double localX, double floorY, double localZ,
                          TrainWalkRole role) {
        this.player = player;
        this.session = session;
        this.vehicle = vehicle;
        this.role = role;
        this.localX = localX;
        this.floorY = floorY;
        this.localZ = localZ;
    }
}
