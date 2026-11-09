/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.train;

import com.unrealtrainmod.compat.ir.IrReadout;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/** Server-thread runtime ownership; never serialized into train or player NBT. */
public final class TrainDriverSession {
    final UUID id = UUID.randomUUID();
    final ServerPlayer player;
    TrainConsist train;
    final UUID vehicle;
    final TrainCarEnd front;
    long sequence;
    int silentTicks;
    String irSoundGroup;
    IrReadout nativeSoundControl;
    int monitorDirection;
    final TrainMonitorPath monitorPath = new TrainMonitorPath();

    TrainDriverSession(ServerPlayer player, TrainConsist train, UUID vehicle, TrainCarEnd front) {
        this.player = player;
        this.train = train;
        this.vehicle = vehicle;
        this.front = front;
        monitorDirection = direction();
    }

    int direction() { return train.member(vehicle).direction() * front.sign(); }

    void setTrain(TrainConsist value) {
        monitorDirection *= train.member(vehicle).direction() * value.member(vehicle).direction();
        train = value;
        monitorPath.invalidate();
    }

    int updateMonitorDirection() {
        if (train.actualSpeed() != 0) monitorDirection = train.actualSpeed() > 0 ? 1 : -1;
        else if (train.control().reverser().direction() != 0) monitorDirection = train.control().reverser().direction();
        return monitorDirection;
    }
}
