/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.train;

import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/** Server-thread runtime ownership; never serialized into train or player NBT. */
public final class TrainDriverSession {
    final UUID id = UUID.randomUUID();
    final ServerPlayer player;
    TrainConsist train;
    final UUID vehicle;
    long sequence;
    int silentTicks;

    TrainDriverSession(ServerPlayer player, TrainConsist train, UUID vehicle) {
        this.player = player;
        this.train = train;
        this.vehicle = vehicle;
    }
}
