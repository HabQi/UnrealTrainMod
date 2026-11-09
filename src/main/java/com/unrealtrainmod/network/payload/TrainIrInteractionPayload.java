/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.network.payload;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.network.payload.TrainIrControlInputPayload.Operation;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** A model component target, independent of boarding and driving sessions. */
public record TrainIrInteractionPayload(UUID vehicle, String component, UUID interaction, long sequence,
                                        float value, Operation operation) implements CustomPacketPayload {
    public static final Type<TrainIrInteractionPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "train_ir_interaction"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TrainIrInteractionPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, value) -> {
                buffer.writeUUID(value.vehicle()); buffer.writeUtf(value.component());
                buffer.writeUUID(value.interaction()); buffer.writeLong(value.sequence());
                buffer.writeFloat(value.value()); buffer.writeEnum(value.operation());
            }, buffer -> new TrainIrInteractionPayload(buffer.readUUID(), buffer.readUtf(), buffer.readUUID(),
                    buffer.readLong(), buffer.readFloat(), buffer.readEnum(Operation.class)));
    @Override public Type<TrainIrInteractionPayload> type() { return TYPE; }
}
