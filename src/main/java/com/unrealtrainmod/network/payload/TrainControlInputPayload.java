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
public record TrainControlInputPayload(UUID session, long sequence, Action action) implements CustomPacketPayload {
    public enum Action { HEARTBEAT, HANDLE_UP, HANDLE_DOWN, REVERSER_NEXT, EMERGENCY_APPLY, EMERGENCY_RELEASE }
    public static final Type<TrainControlInputPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "train_control_input"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TrainControlInputPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, value) -> {
                buffer.writeUUID(value.session());
                buffer.writeLong(value.sequence());
                buffer.writeEnum(value.action());
            }, buffer -> new TrainControlInputPayload(buffer.readUUID(), buffer.readLong(), buffer.readEnum(Action.class)));
    @Override public Type<TrainControlInputPayload> type() { return TYPE; }
}
