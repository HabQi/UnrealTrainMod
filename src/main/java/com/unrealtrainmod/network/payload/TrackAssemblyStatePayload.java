/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.network.payload;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.track.assembly.JunctionState;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record TrackAssemblyStatePayload(ResourceLocation dimension, UUID id, JunctionState state,
                                        long revision) implements CustomPacketPayload {
    public static final Type<TrackAssemblyStatePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "track_assembly_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TrackAssemblyStatePayload> STREAM_CODEC = StreamCodec.of(
            (b, p) -> { b.writeResourceLocation(p.dimension()); b.writeUUID(p.id()); b.writeNbt(p.state().save()); b.writeLong(p.revision()); },
            b -> new TrackAssemblyStatePayload(b.readResourceLocation(), b.readUUID(), JunctionState.load(java.util.Objects.requireNonNull(b.readNbt())), b.readLong()));
    public TrackAssemblyStatePayload {
        if (revision < 0) throw new IllegalArgumentException("Invalid turnout state revision");
    }
    @Override public Type<TrackAssemblyStatePayload> type() { return TYPE; }
}
