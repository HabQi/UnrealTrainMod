/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.network.payload;

import com.unrealtrainmod.UnrealTrainMod;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record TurnoutResultPayload(ResourceLocation dimension, UUID draftId, long sequence,
        Feedback feedback, String detail, boolean closed) implements CustomPacketPayload {
    public enum Feedback {
        READY, CREATED, CANCELLED, INVALID_POINT, INVALID_NODE, NON_HORIZONTAL, NO_PERMISSION, INVALID_ITEM,
        STALE_DRAFT, INVALID_PARAMETERS, BLOCKED_VOLUME, UNLOADED_VOLUME, VOLUME_TOO_LARGE, STORAGE, VEHICLE_PRESENT;
        public String translationKey() {
            return "gui.unrealtrainmod.turnout.feedback." + name().toLowerCase(Locale.ROOT);
        }
    }
    public static final Type<TurnoutResultPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, "turnout_result"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TurnoutResultPayload> STREAM_CODEC = StreamCodec.of(
            (b, p) -> {
                b.writeResourceLocation(p.dimension()); b.writeUUID(p.draftId()); b.writeVarLong(p.sequence());
                b.writeEnum(p.feedback()); b.writeUtf(p.detail()); b.writeBoolean(p.closed());
            }, b -> new TurnoutResultPayload(b.readResourceLocation(), b.readUUID(), b.readVarLong(),
                    b.readEnum(Feedback.class), b.readUtf(), b.readBoolean()));
    @Override public Type<TurnoutResultPayload> type() { return TYPE; }
}
