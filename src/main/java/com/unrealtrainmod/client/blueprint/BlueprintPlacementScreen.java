/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.blueprint;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.blueprint.BlueprintPayloads;
import com.unrealtrainmod.item.BlueprintItem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/** Settings stay on the server while the screen closes to collect track anchors. */
final class BlueprintPlacementScreen extends Screen implements BlueprintClient.ResponseScreen {
    private EditBox spacing;
    private Component feedback = Component.empty();
    private boolean startB, reverse;
    private Button build;
    private int left, top;
    BlueprintPlacementScreen() { super(Component.translatable("blueprint.placement")); }
    @Override protected void init() {
        left = (width - 300) / 2; top = Math.max(4, (height - 230) / 2);
        startB = BlueprintClient.state.getBoolean("StartB"); reverse = BlueprintClient.state.getBoolean("Reverse");
        spacing = new EditBox(font, left + 110, top + 29, 70, 18, Component.translatable("blueprint.spacing"));
        spacing.setMaxLength(12); spacing.setValue(Double.toString(BlueprintClient.state.getDouble("Spacing")));
        if (BlueprintClient.state.isEmpty()) spacing.setValue("32");
        addRenderableWidget(spacing);
        button(left + 186, top + 28, 104, "blueprint.apply_options", () -> options());
        addRenderableWidget(Button.builder(Component.translatable(startB ? "blueprint.start_b" : "blueprint.start_a"), b -> {
            startB = !startB; b.setMessage(Component.translatable(startB ? "blueprint.start_b" : "blueprint.start_a")); options();
        }).bounds(left + 10, top + 53, 132, 20).build());
        addRenderableWidget(Button.builder(Component.translatable(reverse ? "blueprint.reverse" : "blueprint.forward"), b -> {
            reverse = !reverse; b.setMessage(Component.translatable(reverse ? "blueprint.reverse" : "blueprint.forward")); options();
        }).bounds(left + 150, top + 53, 140, 20).build());
        button(left + 10, top + 79, 90, "blueprint.select_line", () -> {
            var d = BlueprintItem.definition(minecraft.player.getMainHandItem()); int line = (BlueprintClient.state.getInt("Line") + 1) % d.tracks().size();
            line(BlueprintPayloads.Action.SELECT_LINE, line);
        });
        button(left + 106, top + 79, 88, "blueprint.undo_point", () -> line(BlueprintPayloads.Action.UNDO_POINT, BlueprintClient.state.getInt("Line")));
        button(left + 200, top + 79, 90, "blueprint.clear_line", () -> line(BlueprintPayloads.Action.CLEAR_LINE, BlueprintClient.state.getInt("Line")));
        button(left + 10, top + 105, 132, "blueprint.pick_tracks", this::onClose);
        button(left + 150, top + 105, 140, "blueprint.generate", () -> {
            if (options()) BlueprintClient.command(BlueprintPayloads.Action.PREVIEW, new CompoundTag());
        });
        build = button(left + 150, top + 198, 140, "blueprint.build", () -> {
            var tag = new CompoundTag(); tag.putUUID("Token", BlueprintClient.state.getUUID("Token"));
            BlueprintClient.command(BlueprintPayloads.Action.BUILD, tag);
        });
        button(left + 10, top + 198, 132, "blueprint.cancel_job", () -> BlueprintClient.command(BlueprintPayloads.Action.CANCEL, new CompoundTag()));
    }
    private Button button(int x, int y, int w, String key, Runnable action) {
        return addRenderableWidget(Button.builder(Component.translatable(key), b -> action.run()).bounds(x, y, w, 20).build());
    }
    private void line(BlueprintPayloads.Action action, int line) {
        var tag = new CompoundTag(); tag.putInt("Line", line); BlueprintClient.command(action, tag);
    }
    private boolean options() {
        try {
            double value = Double.parseDouble(spacing.getValue());
            if (!Double.isFinite(value) || value < .01 || value > 256) throw new NumberFormatException();
            var tag = new CompoundTag(); tag.putDouble("Spacing", value); tag.putBoolean("StartB", startB); tag.putBoolean("Reverse", reverse);
            BlueprintClient.command(BlueprintPayloads.Action.OPTIONS, tag); return true;
        } catch (NumberFormatException e) { feedback = BlueprintClient.feedback("spacing"); return false; }
    }
    public void accept(BlueprintPayloads.Message p) { if (!p.key().isEmpty()) feedback = BlueprintClient.feedback(p.key()); }
    @Override public void render(GuiGraphics g, int mx, int my, float partial) {
        g.fill(left, top, left + 300, top + 226, 0xF018202A);
        g.drawString(font, title, left + 10, top + 10, 0xFFFFFFFF);
        g.drawString(font, Component.translatable("blueprint.spacing"), left + 10, top + 34, 0xFFCCDDEE);
        var state = BlueprintClient.state; var bindings = state.getList("Bindings", 10); var counts = new StringBuilder();
        for (int i = 0; i < bindings.size(); i++) {
            if (i > 0) counts.append("  ");
            counts.append(i == state.getInt("Line") ? "[" : "").append(i + 1).append(":")
                    .append(bindings.getCompound(i).getList("Points", 10).size()).append(i == state.getInt("Line") ? "]" : "");
        }
        g.drawString(font, font.plainSubstrByWidth(Component.translatable("blueprint.bindings", counts.toString()).getString(), 280), left + 10, top + 132, 0xFF99CCEE);
        g.drawString(font, Component.translatable("blueprint.plan_counts", String.format(java.util.Locale.ROOT, "%.2f", state.getDouble("Length")),
                state.getInt("Stations"), state.getInt("Nodes"), state.getInt("Wires")), left + 10, top + 147, 0xFFCCDDEE);
        g.drawWordWrap(font, feedback, left + 10, top + 165, 280, 0xFFFFDD88);
        if (state.getBoolean("Building")) g.drawString(font, Component.translatable("blueprint.progress", state.getInt("Completed"), state.getInt("Stations")), left + 10, top + 185, 0xFF88FFAA);
        build.active = BlueprintClient.ready() && !state.getBoolean("Building");
        super.render(g, mx, my, partial);
    }
    @Override public void tick() {
        if (minecraft.player == null || !minecraft.player.isAlive() || !minecraft.player.getMainHandItem().is(UnrealTrainMod.BLUEPRINT.get())) onClose();
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void renderBackground(GuiGraphics g, int mx, int my, float partial) { }
}
