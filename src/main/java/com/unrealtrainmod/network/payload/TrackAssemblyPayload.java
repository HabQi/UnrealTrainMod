/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.network.payload;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.track.assembly.TrackAssemblyInstance;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** One complete structure per subscribed chunk; no dependency on separately arriving segment batches. */
public record TrackAssemblyPayload(ResourceLocation dimension, int chunkX, int chunkZ,
                                   TrackAssemblyInstance assembly) implements CustomPacketPayload {
    public static final Type<TrackAssemblyPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "track_assembly"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TrackAssemblyPayload> STREAM_CODEC = StreamCodec.of(
            (b, p) -> {
                b.writeResourceLocation(p.dimension()); b.writeInt(p.chunkX()); b.writeInt(p.chunkZ());
                b.writeNbt(p.assembly().saveForClient());
            }, b -> new TrackAssemblyPayload(b.readResourceLocation(), b.readInt(), b.readInt(),
                    TrackAssemblyInstance.load(java.util.Objects.requireNonNull(b.readNbt()))));
    @Override public Type<TrackAssemblyPayload> type() { return TYPE; }
}
