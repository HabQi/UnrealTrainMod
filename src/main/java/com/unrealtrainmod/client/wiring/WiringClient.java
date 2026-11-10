/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.wiring;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.unrealtrainmod.compat.rtm.script.RtmScriptCache;
import com.unrealtrainmod.compat.rtm.script.WireScriptRuntime;
import com.unrealtrainmod.item.WiringItem;
import com.unrealtrainmod.wiring.ConnectorData;
import com.unrealtrainmod.wiring.WireData;
import com.unrealtrainmod.wiring.WiringPayloads;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.LoggerFactory;

public final class WiringClient {
    public interface ResponseScreen { void accept(WiringPayloads.Message message); }
    private static final class ChunkState {
        final Map<UUID, ConnectorData> nodes = new HashMap<>();
        final Map<UUID, WireData> wires = new HashMap<>();
    }
    private static final class Span {
        WireScriptRuntime script;
        boolean attempted, failedGeometry;
        ConnectorData first, second;
        ConnectorData boundsFirst, boundsSecond;
        net.minecraft.world.phys.AABB bounds;
        List<WiringGeometry.Draw> nativeDraws;
        ShapeKey shape;
        void close() { releaseShape(); if (script != null) script.close(); }
        void releaseShape() {
            if (shape == null) return;
            var shared = SHAPES.get(shape); shared.references--; shared.lastUsed = com.unrealtrainmod.client.compat.iris.IrisGeometryCache.frame();
            shape = null;
        }
    }
    private record ShapeKey(WiringModelManager.Model model, Vec3 delta) {}
    private static final class SharedShape {
        final List<WiringGeometry.Draw> draws;
        int references = 1;
        long lastUsed = com.unrealtrainmod.client.compat.iris.IrisGeometryCache.frame();
        SharedShape(List<WiringGeometry.Draw> draws) { this.draws = draws; }
    }
    private static final Map<ShapeKey, SharedShape> SHAPES = new HashMap<>();
    private static final Map<Long, ChunkState> CHUNKS = new HashMap<>();
    private static final Map<Long, ChunkState> PENDING_CHUNKS = new HashMap<>();
    private static final Map<UUID, ConnectorData> NODES = new HashMap<>();
    private static final Map<UUID, WireData> WIRES = new HashMap<>();
    private static final Map<UUID, Span> SPANS = new HashMap<>();
    private static final RtmScriptCache SCRIPTS = new RtmScriptCache();
    private static ConnectorData preview;
    private WiringClient() {}

    public static void register(IEventBus bus) {
        bus.addListener(WiringClient::message); bus.addListener(WiringClient::airUse); bus.addListener(WiringClient::emptyUse);
        bus.addListener(WiringClient::render); bus.addListener(WiringClient::unload); bus.addListener(WiringClient::logout);
    }
    public static void preview(ConnectorData node) { preview = node; }
    public static ConnectorData displayed(ConnectorData node) {
        if (preview != null && node.id().equals(preview.id())) return preview;
        var known = NODES.get(node.id());
        return known != null && known.revision() > node.revision() ? known : node;
    }
    public static ConnectorData node(UUID id) { return NODES.get(id); }
    public static void command(UUID request, WiringPayloads.Action action, CompoundTag data) {
        PacketDistributor.sendToServer(new WiringPayloads.Command(request, action, data));
    }
    private static void message(WiringPayloads.ClientEvent event) {
        var mc = Minecraft.getInstance();
        if (event.payload() instanceof WiringPayloads.Chunk payload) {
            if (mc.level == null || !mc.level.dimension().location().equals(payload.dimension())) return;
            long key = ChunkPos.asLong(payload.x(), payload.z());
            if (payload.unload()) { CHUNKS.remove(key); PENDING_CHUNKS.remove(key); }
            else {
                if (payload.reset()) PENDING_CHUNKS.put(key, new ChunkState());
                var chunk = PENDING_CHUNKS.get(key);
                if (chunk == null) return;
                for (var value : payload.data().getList("Connectors", 10)) {
                    var node = ConnectorData.load((CompoundTag)value); chunk.nodes.put(node.id(), node);
                }
                for (var value : payload.data().getList("Wires", 10)) {
                    var wire = WireData.load((CompoundTag)value); chunk.wires.put(wire.id(), wire);
                }
                if (!payload.complete()) return;
                CHUNKS.put(key, chunk); PENDING_CHUNKS.remove(key);
            }
            var previousWires = new HashSet<>(WIRES.keySet());
            NODES.clear(); WIRES.clear();
            for (var chunk : CHUNKS.values()) {
                chunk.nodes.forEach((id, value) -> NODES.merge(id, value, (a, b) -> a.revision() >= b.revision() ? a : b));
                WIRES.putAll(chunk.wires);
            }
            SPANS.entrySet().removeIf(e -> {
                if (!previousWires.contains(e.getKey()) || WIRES.containsKey(e.getKey())) return false;
                e.getValue().close(); return true;
            });
        } else if (event.payload() instanceof WiringPayloads.Message payload) {
            if (mc.player == null || mc.level == null) return;
            switch (payload.view()) {
                case EDITOR -> mc.setScreen(new ConnectorEditScreen(ConnectorData.load(payload.data())));
                case REMOVAL -> mc.setScreen(new WireRemovalScreen(payload.data()));
                case RESULT -> {
                    if (mc.screen instanceof ResponseScreen screen) screen.accept(payload);
                }
            }
        }
    }
    private static void airUse(PlayerInteractEvent.RightClickItem event) {
        var mc = Minecraft.getInstance();
        if (!event.getLevel().isClientSide || event.getEntity() != mc.player || mc.screen != null
                || event.getHand() != InteractionHand.MAIN_HAND || event.getEntity().isShiftKeyDown()
                || !event.getEntity().mayBuild() || !(event.getItemStack().getItem() instanceof WiringItem item)) return;
        mc.setScreen(new WiringModelScreen(item.wire(), event.getEntity().getInventory().selected));
    }
    private static void emptyUse(PlayerInteractEvent.RightClickEmpty event) {
        if (event.getHand() == InteractionHand.MAIN_HAND && event.getEntity().isShiftKeyDown()
                && event.getEntity().getMainHandItem().isEmpty() && event.getEntity().mayBuild()) {
            command(UUID.randomUUID(), WiringPayloads.Action.CANCEL, new CompoundTag());
        }
    }

    public static List<WiringGeometry.Draw> wireDraws(UUID id, WiringModelManager.Model model,
            ConnectorData first, ConnectorData second, long tick, float partialTick) {
        var span = SPANS.computeIfAbsent(id, key -> new Span());
        boolean share = com.unrealtrainmod.wiring.WiringScriptPolicy.of(model.definition().renderer()).endpointOnly();
        var shape = share ? new ShapeKey(model, second.wirePoint().subtract(first.wirePoint())) : null;
        if (shape != null) {
            if (shape.equals(span.shape)) return SHAPES.get(shape).draws;
            span.releaseShape();
            var cached = SHAPES.get(shape);
            if (cached != null) { cached.references++; cached.lastUsed = com.unrealtrainmod.client.compat.iris.IrisGeometryCache.frame(); span.shape = shape; return cached.draws; }
        }
        if (!span.attempted) {
            span.attempted = true;
            if (model.definition().renderer() != null) try { span.script = new WireScriptRuntime(SCRIPTS, model); }
            catch (Exception | LinkageError e) {
                LoggerFactory.getLogger(WiringClient.class).warn("Unable to initialise wire script {}: {}", model.definition().config(), e.getMessage());
            }
        }
        if (span.script != null) {
            var draws = span.script.render(first, second, tick, partialTick); if (draws != null) return retain(span, shape, draws);
        }
        if (!first.equals(span.first) || !second.equals(span.second)) {
            span.first = first; span.second = second; span.failedGeometry = false;
            try {
                span.nativeDraws = WireShape.build(model.definition(), model.batches(), second.wirePoint().subtract(first.wirePoint()),
                        model.definition().deflection() > 0, new PoseStack(), -1, (count, index) -> true);
            } catch (RuntimeException e) {
                span.failedGeometry = true; span.nativeDraws = List.of();
                LoggerFactory.getLogger(WiringClient.class).warn("Unable to build wire {}: {}", model.definition().config(), e.getMessage());
            }
        }
        return model.definition().empty() || span.failedGeometry ? null : retain(span, shape, span.nativeDraws);
    }

    private static List<WiringGeometry.Draw> retain(Span span, ShapeKey key, List<WiringGeometry.Draw> draws) {
        if (key != null) { SHAPES.put(key, new SharedShape(draws)); span.shape = key; }
        return draws;
    }

    private static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES
                || com.unrealtrainmod.client.compat.iris.IrisCompat.shadowPass()) return;
        if (!WIRES.isEmpty()) renderWires(event);
        WiringRenderCache.flushWorld();
    }
    public static void renderShadow(RenderLevelStageEvent event) { renderWires(event); }
    private static void renderWires(RenderLevelStageEvent event) {
        var mc = Minecraft.getInstance(); if (mc.level == null) return;
        var oldShader = RenderSystem.getShader(); var oldColor = RenderSystem.getShaderColor().clone();
        var modelView = RenderSystem.getModelViewStack(); modelView.pushMatrix(); modelView.set(event.getModelViewMatrix());
        RenderSystem.applyModelViewMatrix();
        try (var bytes = new ByteBufferBuilder(65536)) {
            RenderSystem.setShaderColor(1, 1, 1, 1);
            var buffers = MultiBufferSource.immediate(bytes); var camera = event.getCamera().getPosition();
            float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            for (var wire : WIRES.values()) {
                var a = NODES.get(wire.first()); var b = NODES.get(wire.second()); if (a == null || b == null) continue;
                a = displayed(a); b = displayed(b);
                var bounds = bounds(wire, a, b);
                if (!event.getFrustum().isVisible(bounds)) continue;
                var model = WiringModelManager.INSTANCE.model(wire.model());
                var pose = new PoseStack(); var start = a.wirePoint();
                pose.translate(start.x - camera.x, start.y - camera.y, start.z - camera.z);
                var draws = model == null ? null : wireDraws(wire.id(), model, a, b, mc.level.getGameTime(), partial);
                if (draws == null) { WiringRenderStats.fallback(model == null ? "missing_model" : "wire_geometry_unavailable"); line(pose, buffers, b.wirePoint().subtract(start)); }
                else WiringRenderCache.render(draws, pose, buffers, LevelRenderer.getLightColor(mc.level, BlockPos.containing(start)), false, true);
            }
            buffers.endBatch();
        } finally {
            RenderSystem.setShader(() -> oldShader); RenderSystem.setShaderColor(oldColor[0], oldColor[1], oldColor[2], oldColor[3]);
            modelView.popMatrix(); RenderSystem.applyModelViewMatrix();
        }
    }
    public static net.minecraft.world.phys.AABB bounds(WireData wire, ConnectorData first, ConnectorData second) {
        var span = SPANS.computeIfAbsent(wire.id(), id -> new Span());
        if (!first.equals(span.boundsFirst) || !second.equals(span.boundsSecond)) {
            span.boundsFirst = first; span.boundsSecond = second; span.bounds = wire.bounds(first, second);
        }
        return span.bounds;
    }
    private static void line(PoseStack pose, MultiBufferSource buffers, Vec3 target) {
        var out = buffers.getBuffer(RenderType.lines()); var m = pose.last().pose();
        var d = target.lengthSqr() > 0 ? target.scale(1 / target.length()) : new Vec3(0, 1, 0);
        out.addVertex(m, 0, 0, 0).setColor(0xFFFF00FF).setNormal((float)d.x, (float)d.y, (float)d.z);
        out.addVertex(m, (float)target.x, (float)target.y, (float)target.z).setColor(0xFFFF00FF).setNormal((float)d.x, (float)d.y, (float)d.z);
    }
    static void trimShapes(long frame) { SHAPES.values().removeIf(shape -> shape.references == 0 && shape.lastUsed < frame - 120); }
    public static void clearRuntimes() { SPANS.values().forEach(Span::close); SPANS.clear(); SHAPES.clear(); SCRIPTS.clear(); }
    public static void release(UUID id) { var span = SPANS.remove(id); if (span != null) span.close(); }
    public static void clear() { clearRuntimes(); WiringRenderCache.clear(); CHUNKS.clear(); PENDING_CHUNKS.clear(); NODES.clear(); WIRES.clear(); preview = null; }
    private static void unload(LevelEvent.Unload e) { if (e.getLevel().isClientSide()) clear(); }
    private static void logout(ClientPlayerNetworkEvent.LoggingOut e) { clear(); }
}
