/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.network.payload;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.train.TrainConsist.StopReason;
import com.unrealtrainmod.train.TrainControlState.Reverser;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Only the current driver receives this authoritative snapshot, including input acknowledgement. */
public record TrainControlSyncPayload(UUID session, ResourceLocation dimension, UUID train, UUID vehicle,
                                      boolean open, long sequence, int handle, Reverser reverser,
                                      boolean emergency, double speed, double acceleration, double grade,
                                      double tractionForce, double brakeForce, double tractionPower,
                                      StopReason stopReason, Feedback feedback) implements CustomPacketPayload {
    public enum Feedback { NONE, REVERSER_LOCKED, EMERGENCY_LOCKED }
    public static final Type<TrainControlSyncPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "train_control_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TrainControlSyncPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, value) -> {
                buffer.writeUUID(value.session());
                buffer.writeResourceLocation(value.dimension());
                buffer.writeUUID(value.train());
                buffer.writeUUID(value.vehicle());
                buffer.writeBoolean(value.open());
                buffer.writeLong(value.sequence());
                buffer.writeVarInt(value.handle());
                buffer.writeEnum(value.reverser());
                buffer.writeBoolean(value.emergency());
                buffer.writeDouble(value.speed());
                buffer.writeDouble(value.acceleration());
                buffer.writeDouble(value.grade());
                buffer.writeDouble(value.tractionForce());
                buffer.writeDouble(value.brakeForce());
                buffer.writeDouble(value.tractionPower());
                buffer.writeEnum(value.stopReason());
                buffer.writeEnum(value.feedback());
            }, buffer -> new TrainControlSyncPayload(buffer.readUUID(), buffer.readResourceLocation(), buffer.readUUID(),
                    buffer.readUUID(), buffer.readBoolean(), buffer.readLong(), buffer.readVarInt(), buffer.readEnum(Reverser.class),
                    buffer.readBoolean(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                    buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                    buffer.readEnum(StopReason.class), buffer.readEnum(Feedback.class)));
    @Override public Type<TrainControlSyncPayload> type() { return TYPE; }
}
