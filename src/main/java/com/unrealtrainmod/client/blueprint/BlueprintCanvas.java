/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.blueprint;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.unrealtrainmod.blueprint.BlueprintDefinition;
import com.unrealtrainmod.blueprint.BlueprintPaths;
import com.unrealtrainmod.blueprint.BlueprintPlacementPlan;
import com.unrealtrainmod.client.renderer.preview.PreviewCamera;
import com.unrealtrainmod.client.renderer.preview.PreviewRenderer;
import com.unrealtrainmod.model.ModelBounds;
import com.unrealtrainmod.track.TrackPlacementGeometry;
import com.unrealtrainmod.track.geometry.TrackPose;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.track.mesh.TrackMeshBuilder;
import com.unrealtrainmod.wiring.ConnectorData;
import com.unrealtrainmod.wiring.WireData;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/** Grid and pointer use the exact same orthographic mapping as the model preview. */
final class BlueprintCanvas extends AbstractWidget implements AutoCloseable {
    private record Pick(UUID local, boolean next, ConnectorData node) {}
    private final BlueprintEditorScreen editor;
    private final PreviewRenderer renderer = new PreviewRenderer();
    private final PreviewCamera camera = new PreviewCamera();
    private final BlueprintScene scene = new BlueprintScene();
    private final UUID identity = UUID.randomUUID();
    private final List<Pick> picks = new ArrayList<>();
    private double centerX, centerY = 4, pixels = 22;
    private boolean dragging;
    private double dragX, dragY;
    private ModelBounds bounds = ModelBounds.MISSING;
    private Component diagnostic = Component.empty();
    BlueprintCanvas(BlueprintEditorScreen editor) {
        super(0, 0, 200, 200, Component.translatable("blueprint.canvas")); this.editor = editor;
    }
    void reset() {
        centerX = editor.draft.tracks().stream().mapToDouble(BlueprintDefinition.Reference::x).average().orElse(0);
        centerY = 4; pixels = Math.max(8, Math.min(28, width / Math.max(16, editor.draft.tracks().size() * 6d))); camera.reset();
    }
    private UUID id(String value) { return UUID.nameUUIDFromBytes((identity + ":" + value).getBytes(StandardCharsets.UTF_8)); }
    static double trackY(BlueprintDefinition.Reference ref) {
        return ref.y() + TrackPlacementGeometry.DEFAULT_RAIL_SURFACE_OFFSET;
    }
    void rebuild() {
        scene.close(); picks.clear(); diagnostic = Component.empty();
        var definition = editor.draft; var ns = new ArrayList<ConnectorData>(); var ws = new ArrayList<WireData>();
        boolean both = editor.cross || editor.threeD;
        var paths = new ArrayList<BlueprintPaths.Path>();
        for (var ref : definition.tracks()) paths.add(new BlueprintPaths.Path() {
            public double length() { return 32; }
            public TrackPose sample(double s) { return new TrackPose(new TrackVector(ref.x(), trackY(ref), s), new TrackVector(0, 0, 1),
                    new TrackVector(1, 0, 0), new TrackVector(0, 1, 0), 0, ref.gauge()); }
        });
        try {
            var plan = BlueprintPlacementPlan.generate(definition, paths, 32, editor.variant == 1, identity);
            for (var station : plan.stations()) {
                if (!both && station.index() > 0) break;
                ns.addAll(station.nodes()); ws.addAll(station.wires());
                var section = definition.section(editor.variant + station.index());
                for (int i = 0; i < section.nodes().size(); i++) picks.add(new Pick(section.nodes().get(i).id(), station.index() > 0, station.nodes().get(i)));
            }
        } catch (IllegalArgumentException e) {
            // Incomplete editing drafts still show their existing nodes and resolvable connections.
            diagnostic = BlueprintClient.feedback(e.getMessage() == null ? "definition" : e.getMessage());
            var maps = new ArrayList<HashMap<UUID, ConnectorData>>();
            for (int station = 0; station < (both ? 2 : 1); station++) {
                var section = definition.section(editor.variant + station); var map = new HashMap<UUID, ConnectorData>();
                for (var n : section.nodes()) {
                    var ref = definition.tracks().get(n.track()); var position = n.position().add(ref.x(), trackY(ref), station * 32);
                    var node = new ConnectorData(id(station + ":" + n.id()), n.model(), position, n.face(), Vec3.ZERO, n.angles(), 0);
                    ns.add(node); map.put(n.id(), node); picks.add(new Pick(n.id(), station > 0, node));
                }
                maps.add(map);
            }
            for (int station = 0; station < maps.size(); station++) {
                var section = definition.section(editor.variant + station);
                for (var edge : section.edges()) addWire(ws, maps.get(station), maps.get(station), edge, station + ":in:");
                if (station + 1 < maps.size()) for (var edge : section.outgoing()) addWire(ws, maps.get(station), maps.get(station + 1), edge, station + ":out:");
            }
        }
        scene.set(ns, ws);
        double minX = -2, maxX = 2, minY = -1, maxY = 8, minZ = -2, maxZ = both ? 34 : 2;
        var boxes = new ArrayList<net.minecraft.world.phys.AABB>(); ns.forEach(n -> boxes.add(n.bounds()));
        for (var ref : definition.tracks()) boxes.add(new net.minecraft.world.phys.AABB(ref.x() - ref.gauge(), trackY(ref) - TrackMeshBuilder.RAIL_HEIGHT, -1,
                ref.x() + ref.gauge(), trackY(ref), both ? 33 : 1));
        for (var wire : ws) boxes.add(wire.bounds(scene.nodes.get(wire.first()), scene.nodes.get(wire.second())));
        for (var b : boxes) {
            minX = Math.min(minX, b.minX); maxX = Math.max(maxX, b.maxX); minY = Math.min(minY, b.minY); maxY = Math.max(maxY, b.maxY);
            minZ = Math.min(minZ, b.minZ); maxZ = Math.max(maxZ, b.maxZ);
        }
        bounds = new ModelBounds((float)minX, (float)minY, (float)minZ, (float)maxX, (float)maxY, (float)maxZ);
    }
    private void addWire(List<WireData> output, HashMap<UUID, ConnectorData> from, HashMap<UUID, ConnectorData> to,
                         BlueprintDefinition.Edge edge, String suffix) {
        var a = from.get(edge.first()); var b = to.get(edge.second());
        if (a != null && b != null) output.add(new WireData(id(suffix + edge), a.id(), b.id(), edge.model()));
    }
    private double screenX(double x) { return getX() + width / 2d + (x - centerX) * pixels; }
    private double screenY(double y) { return getY() + height / 2d - (y - centerY) * pixels; }
    private double worldX(double x) { return centerX + (x - getX() - width / 2d) / pixels; }
    private double worldY(double y) { return centerY - (y - getY() - height / 2d) / pixels; }
    @Override protected void renderWidget(GuiGraphics g, int mx, int my, float partial) {
        java.util.function.Consumer<com.mojang.blaze3d.vertex.PoseStack> draw = pose -> {
            try (var bytes = new ByteBufferBuilder(65536)) {
                var buffers = MultiBufferSource.immediate(bytes); scene.render(pose, buffers, true, Vec3.ZERO, null, partial);
                if (editor.threeD) for (var ref : editor.draft.tracks()) {
                    var lines = buffers.getBuffer(RenderType.lines());
                    for (int side : new int[]{-1, 1}) {
                        double x = ref.x() + side * ref.gauge() / 2;
                        double halfWidth = TrackMeshBuilder.RAIL_WIDTH / 2, y = trackY(ref);
                        LevelRenderer.renderLineBox(pose, lines, x - halfWidth, y - TrackMeshBuilder.RAIL_HEIGHT, -1,
                                x + halfWidth, y, 33, .5f, .65f, .8f, 1);
                    }
                }
                buffers.endBatch();
            }
        };
        if (editor.threeD) renderer.render(g, getX(), getY(), width, height, bounds, camera, draw);
        else renderer.renderOrthographic(g, getX(), getY(), width, height, centerX, centerY, pixels, draw);
        g.enableScissor(getX(), getY(), getRight(), getBottom());
        var font = Minecraft.getInstance().font;
        if (!editor.threeD) {
            int fromX = (int)Math.floor(worldX(getX())), toX = (int)Math.ceil(worldX(getRight()));
            int fromY = (int)Math.floor(worldY(getBottom())), toY = (int)Math.ceil(worldY(getY()));
            for (int x = fromX; x <= toX; x++) { int px = (int)screenX(x); g.vLine(px, getY(), getBottom(), x == 0 ? 0xAA6C879B : 0x443D5369); }
            for (int y = fromY; y <= toY; y++) { int py = (int)screenY(y); g.hLine(getX(), getRight(), py, y == 0 ? 0xAA6C879B : 0x443D5369); }
            for (int i = 0; i < editor.draft.tracks().size(); i++) {
                var ref = editor.draft.tracks().get(i); int x = (int)screenX(ref.x()), y = (int)screenY(trackY(ref));
                int half = (int)(ref.gauge() * pixels / 2);
                int railHeight = Math.max(2, (int)Math.ceil(TrackMeshBuilder.RAIL_HEIGHT * pixels));
                g.fill(x - half - 3, y, x - half + 3, y + railHeight, 0xFFC5D0DA); g.fill(x + half - 3, y, x + half + 3, y + railHeight, 0xFFC5D0DA);
                g.hLine(x - half - 6, x + half + 6, y + railHeight + 1, 0xFF889DB0);
                g.drawString(font, "#" + (i + 1) + " G=" + ref.gauge(), x - half, y + railHeight + 4, i == editor.track ? 0xFFFFDD88 : 0xFF99BBDD);
            }
            for (var pick : picks) {
                var point = pick.node.wirePoint(); int x = (int)screenX(point.x), y = (int)screenY(point.y);
                int color = pick.next ? 0xFFFFAA55 : 0xFF55DDFF;
                if (!pick.next && pick.local.equals(editor.firstEndpoint)) color = 0xFF88FF66;
                int radius = pick.next ? 5 : 3;
                g.renderOutline(x - radius, y - radius, radius * 2 + 1, radius * 2 + 1, color);
                g.drawString(font, (pick.next ? "+1:" : "0:") + number(pick), x + 7, y + (pick.next ? 3 : -10), color);
                if (!pick.next && pick.local.equals(editor.selected)) {
                    var p = pick.node.origin(); g.renderOutline((int)screenX(p.x) - 5, (int)screenY(p.y) - 5, 11, 11, 0xFFFFFF66);
                }
            }
        }
        var hint = editor.cross ? Component.translatable("blueprint.cross_hint")
                : Component.translatable("blueprint.grid_hint", TrackPlacementGeometry.DEFAULT_RAIL_SURFACE_OFFSET);
        g.drawWordWrap(font, hint, getX() + 5, getY() + 5, width - 10, 0xFF99CCDD);
        if (!diagnostic.getString().isEmpty()) g.drawString(font, font.plainSubstrByWidth(diagnostic.getString(), width - 10), getX() + 5, getBottom() - 14, 0xFFFFBB66);
        g.disableScissor();
        g.renderOutline(getX(), getY(), width, height, 0xFF527089);
    }
    private int number(Pick pick) {
        var list = pick.next ? editor.next().nodes() : editor.section().nodes();
        for (int i = 0; i < list.size(); i++) if (list.get(i).id().equals(pick.local)) return i + 1;
        return 0;
    }
    @Override public void onClick(double x, double y) {
        dragging = false; if (editor.threeD || Screen.hasShiftDown()) return;
        Pick best = null; double distance = 100;
        for (var pick : picks) {
            if (!editor.wireTool && pick.next) continue;
            // Coincident projected endpoints remain selectable without moving their displayed positions.
            // A cross-span connection always starts here and then selects the next section.
            if (editor.wireTool && editor.cross && pick.next != (editor.firstEndpoint != null)) continue;
            var point = editor.wireTool ? pick.node.wirePoint() : pick.node.origin();
            double dx = screenX(point.x) - x, dy = screenY(point.y) - y;
            double d = dx * dx + dy * dy;
            if (d < distance) { best = pick; distance = d; }
        }
        if (best == null && !editor.wireTool) {
            // Large models can be selected away from their authored origin.
            for (var pick : picks) if (!pick.next) {
                var b = pick.node.bounds(); double wx = worldX(x), wy = worldY(y);
                if (wx >= b.minX && wx <= b.maxX && wy >= b.minY && wy <= b.maxY) { best = pick; break; }
            }
        }
        if (best != null) {
            if (editor.wireTool) editor.endpoint(best.local, best.next);
            else {
                var point = best.node.origin(); dragX = worldX(x) - point.x; dragY = worldY(y) - point.y;
                editor.select(best.local); dragging = true;
            }
        } else if (!editor.wireTool) editor.place(worldX(x), worldY(y));
    }
    @Override protected void onDrag(double x, double y, double dx, double dy) {
        if (editor.threeD) { if (Screen.hasShiftDown()) camera.pan((float)(dx / width), (float)(-dy / height)); else camera.rotate(dx * .6, dy * .6); }
        else if (Screen.hasShiftDown()) { centerX -= dx / pixels; centerY += dy / pixels; }
        else if (dragging) editor.move(worldX(x) - dragX, worldY(y) - dragY);
    }
    @Override public boolean mouseReleased(double x, double y, int b) {
        boolean moved = dragging; dragging = false; if (moved) editor.refreshProperties(); return super.mouseReleased(x, y, b);
    }
    @Override public boolean mouseScrolled(double x, double y, double sx, double sy) {
        if (!isMouseOver(x, y)) return false;
        if (editor.threeD) camera.setZoom(camera.zoom() * Math.pow(1.1, sy));
        else { double wx = worldX(x), wy = worldY(y); pixels = Math.max(4, Math.min(160, pixels * Math.pow(1.1, sy))); centerX += wx - worldX(x); centerY += wy - worldY(y); }
        return true;
    }
    @Override protected void updateWidgetNarration(NarrationElementOutput output) { output.add(NarratedElementType.TITLE, getMessage()); }
    public void close() { scene.close(); renderer.close(); }
}
