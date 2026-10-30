/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.network.payload;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.train.TrainControlState.Reverser;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record TrainControlInputPayload(UUID session, long sequence, int handle, Reverser reverser,
                                       boolean emergency) implements CustomPacketPayload {
    public static final Type<TrainControlInputPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "train_control_input"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TrainControlInputPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, value) -> {
                buffer.writeUUID(value.session());
                buffer.writeLong(value.sequence());
                buffer.writeVarInt(value.handle());
                buffer.writeEnum(value.reverser());
                buffer.writeBoolean(value.emergency());
            }, buffer -> new TrainControlInputPayload(buffer.readUUID(), buffer.readLong(), buffer.readVarInt(),
                    buffer.readEnum(Reverser.class), buffer.readBoolean()));
    @Override public Type<TrainControlInputPayload> type() { return TYPE; }
}
