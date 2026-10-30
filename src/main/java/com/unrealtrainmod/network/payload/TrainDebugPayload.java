/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.network.payload;

import com.unrealtrainmod.UnrealTrainMod;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record TrainDebugPayload(ResourceLocation dimension, List<Component> lines) implements CustomPacketPayload {
    public TrainDebugPayload { lines = List.copyOf(lines); }
    public static final Type<TrainDebugPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "train_debug"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TrainDebugPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeResourceLocation(payload.dimension());
                buffer.writeVarInt(payload.lines().size());
                for (var line : payload.lines()) ComponentSerialization.STREAM_CODEC.encode(buffer, line);
            }, buffer -> {
                var dimension = buffer.readResourceLocation();
                int count = buffer.readVarInt();
                if (count < 0 || count > 24) throw new IllegalArgumentException("Invalid debug line count");
                var lines = new ArrayList<Component>(count);
                for (int i = 0; i < count; i++) lines.add(ComponentSerialization.STREAM_CODEC.decode(buffer));
                return new TrainDebugPayload(dimension, lines);
            });
    @Override public Type<TrainDebugPayload> type() { return TYPE; }
}
