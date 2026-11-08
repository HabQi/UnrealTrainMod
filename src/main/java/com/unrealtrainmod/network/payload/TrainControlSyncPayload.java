/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.network.payload;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.train.TrainConsist.StopReason;
import com.unrealtrainmod.train.TrainCarEnd;
import com.unrealtrainmod.train.TrainControlState.Reverser;
import java.util.ArrayList;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Driver-facing directions and signed motion/force values; includes authoritative input acknowledgement. */
public record TrainControlSyncPayload(UUID session, ResourceLocation dimension, UUID train, UUID vehicle, TrainCarEnd front,
                                      boolean open, long sequence, int powerNotch, int brakeNotch, Reverser reverser,
                                      boolean emergency, double speed, double acceleration, double grade,
                                      UUID monitorEpoch, double monitorDistance, int monitorDirection,
                                      TrainRouteProfile routeProfile,
                                      double tractionForce, double brakeForce,
                                      StopReason stopReason, Feedback feedback) implements CustomPacketPayload {
    public enum Feedback { NONE, REVERSER_LOCKED, EMERGENCY_LOCKED, IR_CONTROL_UNAVAILABLE }
    public static final Type<TrainControlSyncPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "train_control_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TrainControlSyncPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, value) -> {
                buffer.writeUUID(value.session());
                buffer.writeResourceLocation(value.dimension());
                buffer.writeUUID(value.train());
                buffer.writeUUID(value.vehicle());
                buffer.writeEnum(value.front());
                buffer.writeBoolean(value.open());
                buffer.writeLong(value.sequence());
                buffer.writeVarInt(value.powerNotch());
                buffer.writeVarInt(value.brakeNotch());
                buffer.writeEnum(value.reverser());
                buffer.writeBoolean(value.emergency());
                buffer.writeDouble(value.speed());
                buffer.writeDouble(value.acceleration());
                buffer.writeDouble(value.grade());
                buffer.writeUUID(value.monitorEpoch());
                buffer.writeDouble(value.monitorDistance());
                buffer.writeByte(value.monitorDirection());
                buffer.writeVarInt(value.routeProfile().sections().size());
                for (var section : value.routeProfile().sections()) {
                    buffer.writeFloat((float) section.startOffset());
                    buffer.writeFloat((float) section.endOffset());
                    buffer.writeFloat((float) section.grade());
                    buffer.writeFloat((float) section.radius());
                }
                buffer.writeDouble(value.tractionForce());
                buffer.writeDouble(value.brakeForce());
                buffer.writeEnum(value.stopReason());
                buffer.writeEnum(value.feedback());
            }, buffer -> new TrainControlSyncPayload(buffer.readUUID(), buffer.readResourceLocation(), buffer.readUUID(),
                    buffer.readUUID(), buffer.readEnum(TrainCarEnd.class), buffer.readBoolean(), buffer.readLong(), buffer.readVarInt(), buffer.readVarInt(), buffer.readEnum(Reverser.class),
                    buffer.readBoolean(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                    buffer.readUUID(), buffer.readDouble(), buffer.readByte(), readProfile(buffer),
                    buffer.readDouble(), buffer.readDouble(),
                    buffer.readEnum(StopReason.class), buffer.readEnum(Feedback.class)));
    @Override public Type<TrainControlSyncPayload> type() { return TYPE; }

    public TrainControlSyncPayload {
        if (!Double.isFinite(monitorDistance) || (monitorDirection != -1 && monitorDirection != 1)) {
            throw new IllegalArgumentException("Invalid monitor coordinates");
        }
    }

    private static TrainRouteProfile readProfile(RegistryFriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > TrainRouteProfile.MAX_SECTIONS) {
            throw new IllegalArgumentException("Invalid route profile section count");
        }
        var sections = new ArrayList<TrainRouteProfile.Section>(count);
        for (int i = 0; i < count; i++) {
            sections.add(new TrainRouteProfile.Section(buffer.readFloat(), buffer.readFloat(),
                    buffer.readFloat(), buffer.readFloat()));
        }
        return new TrainRouteProfile(sections);
    }
}
