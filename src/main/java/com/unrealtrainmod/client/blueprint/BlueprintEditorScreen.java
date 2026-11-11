/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.blueprint;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.blueprint.BlueprintDefinition;
import com.unrealtrainmod.blueprint.BlueprintPayloads;
import com.unrealtrainmod.client.ClientButtonTextureManager;
import com.unrealtrainmod.client.gui.widget.TrainListWidget;
import com.unrealtrainmod.item.BlueprintItem;
import com.unrealtrainmod.track.TrackPlacementGeometry;
import com.unrealtrainmod.wiring.WiringCatalog;
import com.unrealtrainmod.wiring.WiringDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.DoubleConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** A/B front-elevation recipe editor. The item is changed only by an acknowledged save. */
final class BlueprintEditorScreen extends Screen implements BlueprintClient.ResponseScreen {
    final BlueprintDefinition original;
    BlueprintDefinition draft;
    final int slot;
    final ResourceKey<Level> dimension;
    int variant, track, property;
    boolean wireTool, cross, snap = true, threeD;
    UUID selected, firstEndpoint;
    WiringDefinition resource;
    private int nodeCursor, edgeCursor, targetCursor;
    private UUID pending;
    private float uiScale;
    private int layoutWidth, layoutHeight, right, canvasX, canvasWidth;
    private EditBox name, search;
    private TrainListWidget<WiringDefinition> resources;
    private BlueprintCanvas canvas;
    private Component feedback = Component.empty();
    private final java.util.Set<EditBox> invalidFields = new java.util.HashSet<>();

    BlueprintEditorScreen() {
        super(Component.translatable("blueprint.editor"));
        var mc = Minecraft.getInstance(); original = BlueprintItem.definition(mc.player.getMainHandItem()); draft = original;
        slot = mc.player.getInventory().selected; dimension = mc.level.dimension();
    }
    BlueprintDefinition.Section section() { return variant == 1 && draft.alternating() ? draft.b() : draft.a(); }
    BlueprintDefinition.Section next() { return draft.alternating() ? (variant == 0 ? draft.b() : draft.a()) : draft.a(); }
    BlueprintDefinition.Node node() { return section().nodes().stream().filter(n -> n.id().equals(selected)).findFirst().orElse(null); }
    private void section(BlueprintDefinition.Section s) {
        draft = variant == 0 || !draft.alternating() ? new BlueprintDefinition(draft.name(), draft.alternating(), draft.tracks(), s, draft.b())
                : new BlueprintDefinition(draft.name(), true, draft.tracks(), draft.a(), s);
        changed();
    }
    void changed() { if (canvas != null) canvas.rebuild(); }
    @Override protected void init() {
        propertyLabels.clear();
        invalidFields.clear();
        uiScale = Math.min(1, Math.min(width / 760f, height / 420f));
        layoutWidth = (int)(width / uiScale); layoutHeight = (int)(height / uiScale);
        int listWidth = 170; right = layoutWidth - 218; canvasX = listWidth + 16; canvasWidth = right - canvasX - 8;
        name = new EditBox(font, 8, 8, 150, 20, Component.translatable("blueprint.name"));
        name.setHint(Component.translatable("blueprint.name"));
        name.setMaxLength(80); name.setValue(draft.name()); name.setResponder(value -> draft = new BlueprintDefinition(value, draft.alternating(), draft.tracks(), draft.a(), draft.b())); addRenderableWidget(name);
        button(164, 8, 106, draft.alternating() ? "blueprint.alternating" : "blueprint.single", () -> {
            // Cross-transition rules need explicit endpoints after a mode change.
            var a = new BlueprintDefinition.Section(draft.a().nodes(), draft.a().edges(), List.of());
            var b = new BlueprintDefinition.Section(draft.b().nodes(), draft.b().edges(), List.of());
            draft = new BlueprintDefinition(draft.name(), !draft.alternating(), draft.tracks(), a, b); variant = 0; selected = null; firstEndpoint = null;
            feedback = Component.translatable("blueprint.mode_clears_connections"); rebuild();
        });
        button(276, 8, 80, variant == 0 ? "blueprint.variant_a" : "blueprint.variant_b", () -> {
            variant = 1 - variant; selected = null; firstEndpoint = null; rebuild();
        }).active = draft.alternating();
        button(362, 8, 110, "blueprint.copy_a", () -> {
            var a = new BlueprintDefinition.Section(draft.a().nodes(), draft.a().edges(), List.of());
            draft = new BlueprintDefinition(draft.name(), true, draft.tracks(), a, a); selected = null; firstEndpoint = null;
            feedback = Component.translatable("blueprint.copy_clears_connections"); rebuild();
        });
        button(478, 8, 148, cross ? "blueprint.cross_connections" : "blueprint.section", () -> {
            cross = !cross; selectTool(true);
        });
        button(8, 36, 80, "blueprint.insulators", () -> selectTool(false)).active = wireTool;
        button(92, 36, 86, "blueprint.wires", () -> selectTool(true)).active = !wireTool;
        String query = search == null ? "" : search.getValue();
        search = new EditBox(font, 8, 62, listWidth, 18, Component.translatable("gui.unrealtrainmod.models.search"));
        search.setMaxLength(128); search.setValue(query); addRenderableWidget(search);
        resources = new TrainListWidget<>(minecraft, listWidth, layoutHeight - 126, WiringCatalog.all(wireTool), d -> d.config().archive(),
                d -> Component.literal(d.name()), d -> d.tags() + " " + d.id(), d -> ClientButtonTextureManager.INSTANCE.texture(d.buttonTexture()),
                d -> { resource = d; firstEndpoint = null; feedback = Component.empty(); rebuild(); }, Component.translatable("wiring.unrealtrainmod.empty"), false);
        resources.setRectangle(listWidth, layoutHeight - 126, 8, 84); resources.filter(query); resources.select(resource);
        search.setResponder(resources::filter); addRenderableWidget(resources);
        if (canvas == null) canvas = new BlueprintCanvas(this); canvas.setRectangle(canvasWidth, layoutHeight - 92, canvasX, 62); addRenderableWidget(canvas); canvas.rebuild();
        button(canvasX, 36, 83, "blueprint.reset_view", () -> canvas.reset());
        button(canvasX + 88, 36, 83, snap ? "blueprint.snap_on" : "blueprint.snap_off", () -> { snap = !snap; rebuild(); });
        button(canvasX + 176, 36, 92, threeD ? "blueprint.front_view" : "blueprint.three_d", () -> { threeD = !threeD; rebuild(); });
        button(right, 36, 66, "blueprint.parts", () -> selectTool(false)).active = property != 0;
        button(right + 70, 36, 66, "blueprint.tracks", () -> { property = 1; rebuild(); }).active = property != 1;
        button(right + 140, 36, 66, "blueprint.links", () -> selectTool(true)).active = property != 2;
        if (property == 0) properties(); else if (property == 1) tracks(); else links();
        button(layoutWidth - 212, layoutHeight - 27, 96, "blueprint.save", this::save);
        button(layoutWidth - 110, layoutHeight - 27, 100, "blueprint.close", this::onClose);
    }
    private void rebuild() { rebuildWidgets(); }
    private void selectTool(boolean wires) {
        if (wireTool != wires) resource = null;
        wireTool = wires; if (!wires) cross = false;
        firstEndpoint = null; property = wires ? 2 : 0; rebuild();
    }
    void refreshProperties() { rebuild(); }
    private Button button(int x, int y, int w, String key, Runnable run) {
        var b = addRenderableWidget(Button.builder(Component.translatable(key), ignored -> {
            if (pending == null) { feedback = Component.empty(); run.run(); }
        }).bounds(x, y, w, 20).build());
        b.setTooltip(Tooltip.create(Component.translatable(key + ".hint")));
        return b;
    }
    private record PropertyLabel(Component text, int x, int y) {}
    private final List<PropertyLabel> propertyLabels = new ArrayList<>();
    private void field(String title, double value, int y, double step, DoubleConsumer update) {
        propertyLabels.add(new PropertyLabel(Component.literal(title), right, y + 5));
        var field = new EditBox(font, right + 32, y, 116, 18, Component.literal(title)); field.setMaxLength(24); field.setValue(format(value));
        field.setTooltip(Tooltip.create(Component.translatable("blueprint.field." + (property == 1 ? "track_" : "") + title + ".hint")));
        field.setResponder(text -> {
            try { double n = Double.parseDouble(text); if (!Double.isFinite(n) || Math.abs(n) > 1024) throw new NumberFormatException(); update.accept(n); invalidFields.remove(field); field.setTextColor(0xFFFFFF); }
            catch (NumberFormatException e) { invalidFields.add(field); field.setTextColor(0xFF6666); }
        }); addRenderableWidget(field);
        button(right + 152, y, 24, "blueprint.minus", () -> step(field, -step)); button(right + 180, y, 24, "blueprint.plus", () -> step(field, step));
    }
    private void step(EditBox field, double step) {
        try { field.setValue(format(Double.parseDouble(field.getValue()) + step)); } catch (NumberFormatException ignored) { }
    }
    private static String format(double d) { return String.format(java.util.Locale.ROOT, "%.4f", d).replaceAll("0+$", "").replaceAll("\\.$", ""); }
    private void properties() {
        propertyLabels.clear();
        var nodes = section().nodes();
        addRenderableWidget(new BlueprintPartList(this, right, 64, 206, 62));
        var n = node();
        if (n == null) { propertyLabels.add(new PropertyLabel(Component.translatable("blueprint.place_hint"), right, 138)); return; }
        var definition = WiringCatalog.find(n.model());
        propertyLabels.add(new PropertyLabel(Component.literal((nodes.indexOf(n) + 1) + ": " + (definition == null ? n.model().toString() : definition.name())), right, 132));
        for (int axis = 0; axis < 6; axis++) {
            final int coordinate = axis;
            double value = axis < 3 ? component(n.position(), axis) : component(n.angles(), axis - 3);
            field((axis < 3 ? "" : "R") + "XYZ".charAt(axis % 3), value, 148 + axis * 23, axis < 3 ? .01 : 1, v -> {
                var current = node(); if (current == null) return;
                replace(new BlueprintDefinition.Node(current.id(), current.model(), current.track(), coordinate < 3 ? with(current.position(), coordinate, v) : current.position(),
                        current.face(), coordinate >= 3 ? with(current.angles(), coordinate - 3, v) : current.angles()));
            });
        }
        button(right, 290, 100, "blueprint.bind_track", () -> {
            var current = node(); int t = (current.track() + 1) % draft.tracks().size(); var old = draft.tracks().get(current.track()); var next = draft.tracks().get(t);
            replace(new BlueprintDefinition.Node(current.id(), current.model(), t, current.position().add(old.x() - next.x(), old.y() - next.y(), 0), current.face(), current.angles())); rebuild();
        });
        button(right + 106, 290, 100, "blueprint.install_face", () -> {
            var current = node(); replace(new BlueprintDefinition.Node(current.id(), current.model(), current.track(), current.position(),
                    Direction.values()[(current.face().ordinal() + 1) % Direction.values().length], current.angles())); rebuild();
        });
        propertyLabels.add(new PropertyLabel(Component.literal("#" + (n.track() + 1) + " / " + n.face().getName()), right, 315));
        button(right, 333, 64, "blueprint.duplicate", () -> {
            if (section().nodes().size() >= BlueprintDefinition.MAX_NODES) return;
            var current = node(); var copy = new BlueprintDefinition.Node(UUID.randomUUID(), current.model(), current.track(), current.position().add(.25, 0, 0), current.face(), current.angles());
            var list = new ArrayList<>(section().nodes()); list.add(copy); selected = copy.id(); section(new BlueprintDefinition.Section(list, section().edges(), section().outgoing())); rebuild();
        });
        button(right + 70, 333, 66, "blueprint.replace", () -> {
            var c = node(); replace(new BlueprintDefinition.Node(c.id(), resource.id(), c.track(), c.position(), c.face(), c.angles())); rebuild();
        }).active = resource != null && !resource.wire();
        button(right + 142, 333, 64, "blueprint.delete", this::delete);
    }
    private void tracks() {
        propertyLabels.clear(); track = Math.min(track, draft.tracks().size() - 1); var ref = draft.tracks().get(track);
        propertyLabels.add(new PropertyLabel(Component.translatable("blueprint.reference_number", track + 1, draft.tracks().size()), right, 65));
        button(right, 86, 100, "blueprint.select_track", () -> { track = (track + 1) % draft.tracks().size(); rebuild(); });
        button(right + 106, 86, 100, "blueprint.add_track", () -> {
            if (draft.tracks().size() >= BlueprintDefinition.MAX_TRACKS) return; var refs = new ArrayList<>(draft.tracks());
            refs.add(new BlueprintDefinition.Reference(Integer.toString(refs.size() + 1), refs.getLast().x() + 5, 0, 1.435));
            draft = new BlueprintDefinition(draft.name(), draft.alternating(), refs, draft.a(), draft.b()); track = refs.size() - 1; rebuild();
        });
        field("X", ref.x(), 118, .01, v -> reference(v, null, null));
        field("Y", ref.y(), 144, .01, v -> reference(null, v, null));
        field("G", ref.gauge(), 170, .01, v -> { if (v <= 0 || v > 16) throw new NumberFormatException(); reference(null, null, v); });
        button(right, 201, 206, "blueprint.remove_track", () -> {
            int t = track;
            if (draft.tracks().size() == 1 || java.util.stream.Stream.concat(draft.a().nodes().stream(), draft.b().nodes().stream()).anyMatch(n -> n.track() == t)) {
                feedback = Component.translatable("blueprint.track_in_use"); return;
            }
            var refs = new ArrayList<>(draft.tracks()); refs.remove(t);
            draft = new BlueprintDefinition(draft.name(), draft.alternating(), refs, renumber(draft.a(), t), renumber(draft.b(), t)); track = 0; rebuild();
        });
        propertyLabels.add(new PropertyLabel(Component.translatable("blueprint.track_origin"), right, 234));
        propertyLabels.add(new PropertyLabel(Component.translatable("blueprint.rail_surface", TrackPlacementGeometry.DEFAULT_RAIL_SURFACE_OFFSET), right, 247));
    }
    private static BlueprintDefinition.Section renumber(BlueprintDefinition.Section s, int removed) {
        return new BlueprintDefinition.Section(s.nodes().stream().map(n -> new BlueprintDefinition.Node(n.id(), n.model(), n.track() > removed ? n.track() - 1 : n.track(), n.position(), n.face(), n.angles())).toList(), s.edges(), s.outgoing());
    }
    private void reference(Double x, Double y, Double gauge) {
        var refs = new ArrayList<>(draft.tracks()); var old = refs.get(track);
        refs.set(track, new BlueprintDefinition.Reference(old.name(), x == null ? old.x() : x, y == null ? old.y() : y, gauge == null ? old.gauge() : gauge));
        draft = new BlueprintDefinition(draft.name(), draft.alternating(), refs, draft.a(), draft.b()); changed();
    }
    private void links() {
        propertyLabels.clear();
        var edges = cross ? section().outgoing() : section().edges();
        propertyLabels.add(new PropertyLabel(Component.translatable(cross ? "blueprint.cross_count" : "blueprint.link_count", edges.size()), right, 66));
        button(right, 86, 100, "blueprint.select_link", () -> { if (!edges.isEmpty()) edgeCursor = (edgeCursor + 1) % edges.size(); rebuild(); });
        button(right + 106, 86, 100, "blueprint.delete_link", () -> {
            if (edges.isEmpty()) return; var list = new ArrayList<>(edges); list.remove(Math.min(edgeCursor, list.size() - 1));
            section(new BlueprintDefinition.Section(section().nodes(), cross ? section().edges() : list, cross ? list : section().outgoing())); rebuild();
        });
        if (!edges.isEmpty()) {
            var edge = edges.get(Math.min(edgeCursor, edges.size() - 1)); var definition = WiringCatalog.find(edge.model());
            propertyLabels.add(new PropertyLabel(Component.literal(definition == null ? edge.model().toString() : definition.name()), right, 114));
            propertyLabels.add(new PropertyLabel(Component.literal(endpointLabel(edge.first(), false) + " → " + endpointLabel(edge.second(), cross)), right, 132));
        }
        var source = section().nodes(); var target = cross ? next().nodes() : source;
        button(right, 158, 206, "blueprint.source_endpoint", () -> {
            if (source.isEmpty()) return; nodeCursor = (nodeCursor + 1) % source.size(); firstEndpoint = source.get(nodeCursor).id(); rebuild();
        });
        propertyLabels.add(new PropertyLabel(Component.literal(firstEndpoint == null ? "—" : endpointLabel(firstEndpoint, false)), right, 183));
        button(right, 203, 206, "blueprint.target_endpoint", () -> { if (!target.isEmpty()) targetCursor = (targetCursor + 1) % target.size(); rebuild(); });
        propertyLabels.add(new PropertyLabel(Component.literal(target.isEmpty() ? "—" : endpointLabel(target.get(targetCursor % target.size()).id(), cross)), right, 228));
        button(right, 249, 206, "blueprint.add_connection", () -> connect(target.get(targetCursor % target.size()).id(), cross))
                .active = resource != null && resource.wire() && firstEndpoint != null && !target.isEmpty()
                && (cross || !target.get(targetCursor % target.size()).id().equals(firstEndpoint));
        propertyLabels.add(new PropertyLabel(Component.translatable(cross ? "blueprint.connection_hint" : "blueprint.section_connection_hint"), right, 281));
    }
    private String endpointLabel(UUID id, boolean next) {
        var nodes = next ? next().nodes() : section().nodes();
        String section = (next && draft.alternating() ? variant == 0 : variant == 1) ? "B" : "A";
        for (int i = 0; i < nodes.size(); i++) if (nodes.get(i).id().equals(id))
            return Component.translatable(next ? "blueprint.endpoint_next" : "blueprint.endpoint_current", section, i + 1).getString();
        return "?";
    }
    void select(UUID id) { selected = id; property = 0; rebuild(); }
    void place(double x, double y) {
        if (pending != null || resource == null || resource.wire() || cross || section().nodes().size() >= BlueprintDefinition.MAX_NODES) return;
        var ref = draft.tracks().get(track);
        var n = new BlueprintDefinition.Node(UUID.randomUUID(), resource.id(), track, new Vec3(snap(x) - ref.x(), snap(y) - BlueprintCanvas.trackY(ref), 0), Direction.UP, Vec3.ZERO);
        var nodes = new ArrayList<>(section().nodes()); nodes.add(n); selected = n.id();
        section(new BlueprintDefinition.Section(nodes, section().edges(), section().outgoing())); property = 0; rebuild();
    }
    double snap(double v) { return snap ? Math.round(v * 4) / 4d : v; }
    void move(double x, double y) {
        var n = node(); if (n == null || pending != null) return; var ref = draft.tracks().get(n.track());
        replace(new BlueprintDefinition.Node(n.id(), n.model(), n.track(), new Vec3(snap(x) - ref.x(), snap(y) - BlueprintCanvas.trackY(ref), n.position().z), n.face(), n.angles()));
    }
    private void replace(BlueprintDefinition.Node node) {
        section(new BlueprintDefinition.Section(section().nodes().stream().map(n -> n.id().equals(node.id()) ? node : n).toList(), section().edges(), section().outgoing()));
    }
    void endpoint(UUID id, boolean next) {
        if (firstEndpoint == null) {
            if (next) return; firstEndpoint = id; rebuild();
        } else connect(id, next);
    }
    private void connect(UUID target, boolean next) {
        if (pending != null || resource == null || !resource.wire() || firstEndpoint == null || next != cross || !cross && target.equals(firstEndpoint)) return;
        var list = new ArrayList<>(cross ? section().outgoing() : section().edges());
        var edge = new BlueprintDefinition.Edge(firstEndpoint, target, resource.id());
        if (list.stream().anyMatch(e -> e.model().equals(edge.model()) && (e.first().equals(edge.first()) && e.second().equals(edge.second())
                || !cross && e.first().equals(edge.second()) && e.second().equals(edge.first())))) { feedback = BlueprintClient.feedback("duplicate"); return; }
        if (section().edges().size() + section().outgoing().size() >= BlueprintDefinition.MAX_EDGES) return;
        list.add(edge); section(new BlueprintDefinition.Section(section().nodes(), cross ? section().edges() : list, cross ? list : section().outgoing()));
        firstEndpoint = null; edgeCursor = list.size() - 1; feedback = Component.translatable("blueprint.connection_added"); rebuild();
    }
    private void delete() {
        if (selected == null) return; UUID id = selected;
        var current = section();
        var cleaned = new BlueprintDefinition.Section(current.nodes().stream().filter(n -> !n.id().equals(id)).toList(),
                current.edges().stream().filter(e -> !e.first().equals(id) && !e.second().equals(id)).toList(),
                current.outgoing().stream().filter(e -> !e.first().equals(id) && (draft.alternating() || !e.second().equals(id))).toList());
        if (draft.alternating()) {
            var other = next(); other = new BlueprintDefinition.Section(other.nodes(), other.edges(), other.outgoing().stream().filter(e -> !e.second().equals(id)).toList());
            draft = variant == 0 ? new BlueprintDefinition(draft.name(), true, draft.tracks(), cleaned, other)
                    : new BlueprintDefinition(draft.name(), true, draft.tracks(), other, cleaned);
        } else draft = new BlueprintDefinition(draft.name(), false, draft.tracks(), cleaned, draft.b());
        selected = null; firstEndpoint = null; rebuild();
    }
    private void save() {
        try {
            if (!invalidFields.isEmpty()) throw new IllegalArgumentException("definition");
            draft.validate(false); var tag = new CompoundTag(); tag.putInt("Slot", slot); tag.put("Original", original.save()); tag.put("Definition", draft.save());
            pending = BlueprintClient.command(BlueprintPayloads.Action.SAVE, tag); feedback = Component.translatable("blueprint.saving");
            for (var child : children()) if (child instanceof net.minecraft.client.gui.components.AbstractWidget widget) {
                widget.active = false; if (widget instanceof EditBox field) field.setEditable(false);
            }
        } catch (IllegalArgumentException e) { feedback = BlueprintClient.feedback("definition"); }
    }
    public void accept(BlueprintPayloads.Message p) {
        if (p.request().equals(pending)) { pending = null; if (p.key().equals("saved")) onClose(); else { feedback = BlueprintClient.feedback(p.key()); rebuild(); } }
    }
    static double component(Vec3 v, int i) { return i == 0 ? v.x : i == 1 ? v.y : v.z; }
    private static Vec3 with(Vec3 v, int i, double n) { return new Vec3(i == 0 ? n : v.x, i == 1 ? n : v.y, i == 2 ? n : v.z); }
    @Override public void render(GuiGraphics g, int mx, int my, float partial) {
        g.flush(); var scaled = new ScaledGraphics(minecraft, g, uiScale);
        scaled.pose().scale(uiScale, uiScale, 1);
        scaled.fill(0, 0, layoutWidth, layoutHeight, 0xFF141B24);
        super.render(scaled, (int)(mx / uiScale), (int)(my / uiScale), partial);
        for (var label : propertyLabels) scaled.drawString(font, font.plainSubstrByWidth(label.text.getString(), 206), label.x, label.y, 0xFFCCDDEE);
        String text = feedback.getString();
        if (text.isEmpty()) {
            String key = threeD ? "blueprint.inspect_hint" : wireTool ? (cross ? "blueprint.cross_tool_hint" : "blueprint.wire_tool_hint") : "blueprint.editor_hint";
            text = Component.translatable(key).getString();
            if (resource != null) text = resource.name() + " | " + text;
        }
        scaled.drawString(font, font.plainSubstrByWidth(text, layoutWidth - 232), 8, layoutHeight - 20, 0xFFFFDD88);
        scaled.flush();
    }
    /** GuiGraphics scissors use screen coordinates, independently of its pose matrix. */
    private static final class ScaledGraphics extends GuiGraphics {
        private final float scale;
        ScaledGraphics(Minecraft mc, GuiGraphics original, float scale) { super(mc, original.bufferSource()); this.scale = scale; }
        @Override public void enableScissor(int x, int y, int right, int bottom) {
            super.enableScissor((int)Math.floor(x * scale), (int)Math.floor(y * scale), (int)Math.ceil(right * scale), (int)Math.ceil(bottom * scale));
        }
        @Override public boolean containsPointInScissor(int x, int y) { return super.containsPointInScissor((int)(x * scale), (int)(y * scale)); }
    }
    @Override public boolean mouseClicked(double x, double y, int b) { return super.mouseClicked(x / uiScale, y / uiScale, b); }
    @Override public boolean mouseReleased(double x, double y, int b) { return super.mouseReleased(x / uiScale, y / uiScale, b); }
    @Override public boolean mouseDragged(double x, double y, int b, double dx, double dy) { return super.mouseDragged(x / uiScale, y / uiScale, b, dx / uiScale, dy / uiScale); }
    @Override public boolean mouseScrolled(double x, double y, double sx, double sy) { return super.mouseScrolled(x / uiScale, y / uiScale, sx, sy); }
    @Override public void mouseMoved(double x, double y) { super.mouseMoved(x / uiScale, y / uiScale); }
    @Override public void tick() {
        if (minecraft.player == null || minecraft.level == null || !minecraft.level.dimension().equals(dimension) || !minecraft.player.isAlive()
                || !minecraft.player.mayBuild() || minecraft.player.getInventory().selected != slot || !minecraft.player.getMainHandItem().is(UnrealTrainMod.BLUEPRINT.get())) onClose();
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void renderBackground(GuiGraphics g, int mx, int my, float partial) { }
    @Override public void removed() { if (canvas != null) canvas.close(); super.removed(); }
}
