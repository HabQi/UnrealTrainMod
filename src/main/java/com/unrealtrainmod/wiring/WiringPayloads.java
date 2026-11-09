/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.wiring;

import com.unrealtrainmod.UnrealTrainMod;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** The command kind determines the required, boundary-validated NBT fields. */
public final class WiringPayloads {
    public enum Action { SELECT_CONNECTOR, SELECT_WIRE, EDIT, REMOVE_WIRE, CANCEL }
    public enum View { RESULT, EDITOR, REMOVAL }
    private WiringPayloads() {}
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, path); }
    private static CompoundTag read(RegistryFriendlyByteBuf buffer) {
        var tag = buffer.readNbt();
        if (tag == null) throw new IllegalArgumentException("Missing wiring payload");
        return tag;
    }

    public record Command(UUID requestId, Action action, CompoundTag data) implements CustomPacketPayload {
        public static final Type<Command> TYPE = new Type<>(id("wiring_command"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Command> STREAM_CODEC = StreamCodec.of(
                (b, p) -> { b.writeUUID(p.requestId); b.writeEnum(p.action); b.writeNbt(p.data); },
                b -> new Command(b.readUUID(), b.readEnum(Action.class), read(b)));
        @Override public Type<Command> type() { return TYPE; }
    }
    public record Message(UUID requestId, View view, boolean success, String key, CompoundTag data) implements CustomPacketPayload {
        public static final Type<Message> TYPE = new Type<>(id("wiring_message"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Message> STREAM_CODEC = StreamCodec.of(
                (b, p) -> { b.writeUUID(p.requestId); b.writeEnum(p.view); b.writeBoolean(p.success); b.writeUtf(p.key); b.writeNbt(p.data); },
                b -> new Message(b.readUUID(), b.readEnum(View.class), b.readBoolean(), b.readUtf(256), read(b)));
        @Override public Type<Message> type() { return TYPE; }
    }
    /** Bounded batches; clients publish the replacement only after its final batch. */
    public record Chunk(ResourceLocation dimension, int x, int z, boolean reset, boolean complete, boolean unload, CompoundTag data)
            implements CustomPacketPayload {
        public static final Type<Chunk> TYPE = new Type<>(id("wiring_chunk"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Chunk> STREAM_CODEC = StreamCodec.of(
                (b, p) -> { b.writeResourceLocation(p.dimension); b.writeInt(p.x); b.writeInt(p.z);
                    b.writeBoolean(p.reset); b.writeBoolean(p.complete); b.writeBoolean(p.unload); b.writeNbt(p.data); },
                b -> new Chunk(b.readResourceLocation(), b.readInt(), b.readInt(), b.readBoolean(), b.readBoolean(), b.readBoolean(), read(b)));
        @Override public Type<Chunk> type() { return TYPE; }
    }

    public static final class ClientEvent extends net.neoforged.bus.api.Event {
        private final CustomPacketPayload payload;
        public ClientEvent(CustomPacketPayload payload) { this.payload = payload; }
        public CustomPacketPayload payload() { return payload; }
    }

    public static void register(PayloadRegistrar registrar) {
        registrar.playToServer(Command.TYPE, Command.STREAM_CODEC, (p, c) -> WiringManager.command(p, c));
        registrar.playToClient(Message.TYPE, Message.STREAM_CODEC, (p, c) -> NeoForge.EVENT_BUS.post(new ClientEvent(p)));
        registrar.playToClient(Chunk.TYPE, Chunk.STREAM_CODEC, (p, c) -> NeoForge.EVENT_BUS.post(new ClientEvent(p)));
    }
}
