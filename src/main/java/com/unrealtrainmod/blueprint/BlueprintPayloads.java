/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.blueprint;

import com.unrealtrainmod.UnrealTrainMod;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class BlueprintPayloads {
    public enum Action { SAVE, STATUS, SELECT_LINE, UNDO_POINT, CLEAR_LINE, OPTIONS, PREVIEW, BUILD, CANCEL }
    public enum View { RESULT, STATE, PLAN, PROGRESS }
    private BlueprintPayloads() {}
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(UnrealTrainMod.MODID, path); }
    private static CompoundTag read(RegistryFriendlyByteBuf b) {
        var tag = b.readNbt(); if (tag == null) throw new IllegalArgumentException("Missing blueprint payload"); return tag;
    }
    public record Command(UUID request, Action action, CompoundTag data) implements CustomPacketPayload {
        public static final Type<Command> TYPE = new Type<>(id("blueprint_command"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Command> STREAM_CODEC = StreamCodec.of(
                (b, p) -> { b.writeUUID(p.request); b.writeEnum(p.action); b.writeNbt(p.data); },
                b -> new Command(b.readUUID(), b.readEnum(Action.class), read(b)));
        public Type<Command> type() { return TYPE; }
    }
    public record Message(UUID request, View view, String key, CompoundTag data) implements CustomPacketPayload {
        public static final Type<Message> TYPE = new Type<>(id("blueprint_message"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Message> STREAM_CODEC = StreamCodec.of(
                (b, p) -> { b.writeUUID(p.request); b.writeEnum(p.view); b.writeUtf(p.key); b.writeNbt(p.data); },
                b -> new Message(b.readUUID(), b.readEnum(View.class), b.readUtf(256), read(b)));
        public Type<Message> type() { return TYPE; }
    }
    public static final class ClientEvent extends net.neoforged.bus.api.Event {
        private final Message message;
        public ClientEvent(Message message) { this.message = message; }
        public Message message() { return message; }
    }
    public static void register(PayloadRegistrar registrar) {
        registrar.playToServer(Command.TYPE, Command.STREAM_CODEC, BlueprintManager::command);
        registrar.playToClient(Message.TYPE, Message.STREAM_CODEC, (p, c) -> NeoForge.EVENT_BUS.post(new ClientEvent(p)));
    }
}
