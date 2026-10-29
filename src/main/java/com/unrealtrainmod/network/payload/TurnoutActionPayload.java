/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.network.payload;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.track.assembly.TrackAssemblyDefinition;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record TurnoutActionPayload(ResourceLocation dimension, UUID draftId, long sequence, Action action,
        TrackAssemblyDefinition definition, double yaw) implements CustomPacketPayload {
    public enum Action { UPDATE, CONFIRM, CANCEL }
    public static final Type<TurnoutActionPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "turnout_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TurnoutActionPayload> STREAM_CODEC = StreamCodec.of(
            (b, p) -> {
                b.writeResourceLocation(p.dimension()); b.writeUUID(p.draftId()); b.writeVarLong(p.sequence());
                b.writeEnum(p.action()); b.writeEnum(p.definition()); b.writeDouble(p.yaw());
            }, b -> new TurnoutActionPayload(b.readResourceLocation(), b.readUUID(), b.readVarLong(),
                    b.readEnum(Action.class), b.readEnum(TrackAssemblyDefinition.class), b.readDouble()));
    @Override public Type<TurnoutActionPayload> type() { return TYPE; }
}
