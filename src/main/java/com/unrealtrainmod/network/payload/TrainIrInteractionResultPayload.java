/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.network.payload;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.network.payload.TrainControlSyncPayload.Feedback;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record TrainIrInteractionResultPayload(UUID vehicle, UUID interaction, long sequence, Feedback feedback)
        implements CustomPacketPayload {
    public static final Type<TrainIrInteractionResultPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "train_ir_interaction_result"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TrainIrInteractionResultPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, value) -> {
                buffer.writeUUID(value.vehicle()); buffer.writeUUID(value.interaction());
                buffer.writeLong(value.sequence()); buffer.writeEnum(value.feedback());
            }, buffer -> new TrainIrInteractionResultPayload(buffer.readUUID(), buffer.readUUID(), buffer.readLong(), buffer.readEnum(Feedback.class)));
    @Override public Type<TrainIrInteractionResultPayload> type() { return TYPE; }
}
