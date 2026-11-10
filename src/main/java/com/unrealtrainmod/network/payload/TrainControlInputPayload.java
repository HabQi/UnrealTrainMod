/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.network.payload;

import com.unrealtrainmod.UnrealTrainMod;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Discrete operations on the latest server control state; heartbeats never write controls. */
public record TrainControlInputPayload(UUID session, long sequence, Action action, int notch) implements CustomPacketPayload {
    /**
     * Door actions carry an explicit target, so a repeated request keeps the same state instead of flipping
     * the door back on the server.
     */
    public enum Action {
        HEARTBEAT, HANDLE_UP, HANDLE_DOWN, REVERSER_NEXT, EMERGENCY_APPLY, EMERGENCY_RELEASE,
        DOOR_LEFT_OPEN, DOOR_LEFT_CLOSE, DOOR_RIGHT_OPEN, DOOR_RIGHT_CLOSE,
        POWER_SET, BRAKE_SET, POWER_UP, POWER_DOWN, BRAKE_UP, BRAKE_DOWN,
        HEAD_LIGHT_ON, HEAD_LIGHT_OFF, TAIL_LIGHT_ON, TAIL_LIGHT_OFF,
        POWER_HANDLE_BEGIN, BRAKE_HANDLE_BEGIN, HANDLE_RELEASE, REVERSER_SET
    }
    public static final Type<TrainControlInputPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "train_control_input"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TrainControlInputPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, value) -> {
                buffer.writeUUID(value.session());
                buffer.writeLong(value.sequence());
                buffer.writeEnum(value.action());
                buffer.writeVarInt(value.notch());
            }, buffer -> new TrainControlInputPayload(buffer.readUUID(), buffer.readLong(), buffer.readEnum(Action.class),
                    buffer.readVarInt()));
    @Override public Type<TrainControlInputPayload> type() { return TYPE; }
}
