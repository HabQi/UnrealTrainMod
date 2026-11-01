/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.network.payload;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.train.PassengerPose;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Explicit posture request for the sender's own passenger session. The request never names another
 * player. A zero delta sets the posture; a nonzero delta only adjusts an already lying passenger's
 * height by one step and never changes posture.
 */
public record TrainPassengerPosePayload(UUID session, PassengerPose pose, int heightStepDelta)
        implements CustomPacketPayload {
    public static final Type<TrainPassengerPosePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "train_passenger_pose"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TrainPassengerPosePayload> STREAM_CODEC = StreamCodec.of(
            (buffer, value) -> {
                buffer.writeUUID(value.session());
                buffer.writeVarInt(value.pose().ordinal());
                buffer.writeVarInt(value.heightStepDelta());
            }, buffer -> new TrainPassengerPosePayload(
                    buffer.readUUID(), PassengerPose.byId(buffer.readVarInt()), buffer.readVarInt()));

    @Override
    public Type<TrainPassengerPosePayload> type() {
        return TYPE;
    }
}
