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

/** A visual control target for the vehicle owned by the driver's current session. */
public record TrainIrControlInputPayload(UUID session, long sequence, String group, float value, boolean pulse)
        implements CustomPacketPayload {
    public static final Type<TrainIrControlInputPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "train_ir_control_input"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TrainIrControlInputPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, value) -> {
                buffer.writeUUID(value.session());
                buffer.writeLong(value.sequence());
                buffer.writeUtf(value.group());
                buffer.writeFloat(value.value());
                buffer.writeBoolean(value.pulse());
            }, buffer -> new TrainIrControlInputPayload(buffer.readUUID(), buffer.readLong(), buffer.readUtf(),
                    buffer.readFloat(), buffer.readBoolean()));
    @Override public Type<TrainIrControlInputPayload> type() { return TYPE; }
}
