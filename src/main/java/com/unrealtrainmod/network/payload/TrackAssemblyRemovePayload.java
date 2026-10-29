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

public record TrackAssemblyRemovePayload(ResourceLocation dimension, UUID id) implements CustomPacketPayload {
    public static final Type<TrackAssemblyRemovePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "track_assembly_remove"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TrackAssemblyRemovePayload> STREAM_CODEC = StreamCodec.of(
            (b, p) -> { b.writeResourceLocation(p.dimension()); b.writeUUID(p.id()); },
            b -> new TrackAssemblyRemovePayload(b.readResourceLocation(), b.readUUID()));
    @Override public Type<TrackAssemblyRemovePayload> type() { return TYPE; }
}
