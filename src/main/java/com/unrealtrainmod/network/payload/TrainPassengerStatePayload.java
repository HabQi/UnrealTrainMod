/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.network.payload;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.train.PassengerPose;
import com.unrealtrainmod.train.TrainWalkRole;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Authoritative vehicle-local walking point and posture for either a driver or ordinary passenger.
 * The sequence acknowledges the last input applied by the server; the tick orders snapshots across
 * a quick leave-and-board sequence.
 */
public record TrainPassengerStatePayload(UUID player, UUID vehicle, ResourceLocation dimension,
                                        UUID session, boolean present, long sequence, long tick,
                                        double localX, double floorY, double localZ, PassengerPose pose, int heightStep,
                                        TrainWalkRole role)
        implements CustomPacketPayload {
    public static final Type<TrainPassengerStatePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "train_passenger_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TrainPassengerStatePayload> STREAM_CODEC = StreamCodec.of(
            (buffer, value) -> {
                buffer.writeUUID(value.player());
                buffer.writeUUID(value.vehicle());
                buffer.writeResourceLocation(value.dimension());
                buffer.writeUUID(value.session());
                buffer.writeBoolean(value.present());
                buffer.writeLong(value.sequence());
                buffer.writeLong(value.tick());
                buffer.writeDouble(value.localX());
                buffer.writeDouble(value.floorY());
                buffer.writeDouble(value.localZ());
                buffer.writeVarInt(value.pose().ordinal());
                buffer.writeVarInt(value.heightStep());
                buffer.writeVarInt(value.role().ordinal());
            }, buffer -> new TrainPassengerStatePayload(buffer.readUUID(), buffer.readUUID(),
                    buffer.readResourceLocation(), buffer.readUUID(), buffer.readBoolean(),
                    buffer.readLong(), buffer.readLong(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                    PassengerPose.byId(buffer.readVarInt()), buffer.readVarInt(),
                    TrainWalkRole.byId(buffer.readVarInt())));
    @Override public Type<TrainPassengerStatePayload> type() { return TYPE; }
}
