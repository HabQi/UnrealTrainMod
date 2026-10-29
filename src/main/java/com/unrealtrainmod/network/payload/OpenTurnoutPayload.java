/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.network.payload;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.track.TrackPlacementEndpoint;
import com.unrealtrainmod.track.assembly.TrackAssemblyDefinition;
import com.unrealtrainmod.track.geometry.TrackVector;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** A private, fixed-origin draft, independent of the synchronized track graph. */
public record OpenTurnoutPayload(ResourceLocation dimension, UUID draftId, TrackPlacementEndpoint origin,
        double gauge, TrackAssemblyDefinition definition, double yaw, boolean locked,
        TurnoutResultPayload.Feedback feedback, String detail) implements CustomPacketPayload {
    public static final Type<OpenTurnoutPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "open_turnout"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenTurnoutPayload> STREAM_CODEC = StreamCodec.of(
            (b, p) -> {
                b.writeResourceLocation(p.dimension()); b.writeUUID(p.draftId());
                b.writeBoolean(p.origin().nodeId() != null);
                if (p.origin().nodeId() != null) b.writeUUID(p.origin().nodeId());
                var v = p.origin().position(); b.writeDouble(v.x()); b.writeDouble(v.y()); b.writeDouble(v.z());
                b.writeDouble(p.gauge()); b.writeEnum(p.definition()); b.writeDouble(p.yaw()); b.writeBoolean(p.locked());
                b.writeEnum(p.feedback()); b.writeUtf(p.detail());
            }, b -> {
                var dimension = b.readResourceLocation(); var id = b.readUUID();
                var node = b.readBoolean() ? b.readUUID() : null;
                var origin = new TrackPlacementEndpoint(node, new TrackVector(b.readDouble(), b.readDouble(), b.readDouble()));
                return new OpenTurnoutPayload(dimension, id, origin, b.readDouble(), b.readEnum(TrackAssemblyDefinition.class),
                        b.readDouble(), b.readBoolean(), b.readEnum(TurnoutResultPayload.Feedback.class), b.readUtf());
            });
    @Override public Type<OpenTurnoutPayload> type() { return TYPE; }
}
