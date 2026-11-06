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

/** One walking step in the vehicle-local plane, consumed once in sequence by the server. */
public record TrainWalkInputPayload(UUID session, long sequence, float localX, float localZ)
        implements CustomPacketPayload {
    public static final int MAX_PENDING_INPUTS = 20;
    public static final Type<TrainWalkInputPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "train_walk_input"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TrainWalkInputPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, value) -> {
                buffer.writeUUID(value.session());
                buffer.writeLong(value.sequence());
                buffer.writeFloat(value.localX());
                buffer.writeFloat(value.localZ());
            }, buffer -> new TrainWalkInputPayload(buffer.readUUID(), buffer.readLong(),
                    buffer.readFloat(), buffer.readFloat()));
    @Override public Type<TrainWalkInputPayload> type() { return TYPE; }
}
