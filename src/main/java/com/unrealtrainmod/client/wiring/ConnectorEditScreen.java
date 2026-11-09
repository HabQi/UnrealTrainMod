/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.wiring;

import com.unrealtrainmod.UnrealTrainMod;
import com.unrealtrainmod.wiring.ConnectorData;
import com.unrealtrainmod.wiring.WiringPayloads;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public final class ConnectorEditScreen extends Screen implements WiringClient.ResponseScreen {
    private final ConnectorData original;
    private final ResourceKey<Level> dimension;
    private final String[] values = new String[6];
    private final EditBox[] fields = new EditBox[6];
    private Button apply;
    private UUID pending;
    private Component status = Component.empty();
    private int left, top;
    public ConnectorEditScreen(ConnectorData original) {
        super(Component.translatable("wiring.unrealtrainmod.edit")); this.original = original;
        dimension = net.minecraft.client.Minecraft.getInstance().level.dimension(); resetValues();
    }
    private void resetValues() {
        double[] numbers = {original.offset().x, original.offset().y, original.offset().z, original.angles().x, original.angles().y, original.angles().z};
        for (int i = 0; i < 6; i++) values[i] = Double.toString(numbers[i]);
    }
    @Override protected void init() {
        left = Math.max(4, width - 254); top = Math.max(4, (height - 228) / 2);
        for (int i = 0; i < 6; i++) {
            final int index = i; int y = top + 30 + i * 24;
            fields[i] = new EditBox(font, left + 76, y, 112, 18, Component.literal((i < 3 ? "Offset " : "Angle ") + "XYZ".charAt(i % 3)));
            fields[i].setMaxLength(32); fields[i].setValue(values[i]);
            fields[i].setResponder(value -> { values[index] = value; updatePreview(); }); addRenderableWidget(fields[i]);
            addRenderableWidget(Button.builder(Component.literal("−"), b -> step(index, -1)).bounds(left + 192, y, 22, 18).build());
            addRenderableWidget(Button.builder(Component.literal("+"), b -> step(index, 1)).bounds(left + 218, y, 22, 18).build());
        }
        apply = addRenderableWidget(Button.builder(Component.translatable("wiring.unrealtrainmod.apply"), b -> submit())
                .bounds(left + 8, top + 184, 74, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("wiring.unrealtrainmod.reset"), b -> {
            if (pending != null) return; resetValues(); for (int i = 0; i < 6; i++) fields[i].setValue(values[i]); updatePreview();
        }).bounds(left + 86, top + 184, 74, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("wiring.unrealtrainmod.cancel"), b -> onClose())
                .bounds(left + 164, top + 184, 74, 20).build());
        updatePreview();
    }
    private ConnectorData draft() {
        try {
            var numbers = new double[6];
            for (int i = 0; i < 6; i++) { numbers[i] = Double.parseDouble(values[i]); if (!Double.isFinite(numbers[i])) return null; }
            var result = original.edited(new Vec3(numbers[0], numbers[1], numbers[2]), new Vec3(numbers[3] % 360, numbers[4] % 360, numbers[5] % 360));
            var pos = net.minecraft.core.BlockPos.containing(result.origin());
            if (minecraft.level.isOutsideBuildHeight(pos) || !minecraft.level.getWorldBorder().isWithinBounds(pos)) return null;
            return result;
        } catch (NumberFormatException e) { return null; }
    }
    private void updatePreview() {
        var draft = draft(); if (draft != null) WiringClient.preview(draft);
        if (apply != null) apply.active = pending == null && draft != null;
        for (var field : fields) if (field != null) field.setEditable(pending == null);
    }
    private void step(int index, int direction) {
        if (pending != null) return;
        try { fields[index].setValue(Double.toString(Double.parseDouble(values[index]) + direction * (index < 3 ? .01 : 1))); }
        catch (NumberFormatException ignored) { }
    }
    private void submit() {
        var draft = draft(); if (draft == null) return;
        var tag = original.save(); ConnectorData.putVector(tag, "Offset", draft.offset()); ConnectorData.putVector(tag, "Angles", draft.angles());
        pending = UUID.randomUUID(); WiringClient.command(pending, WiringPayloads.Action.EDIT, tag); updatePreview();
    }
    @Override public void accept(WiringPayloads.Message result) {
        if (!result.requestId().equals(pending)) return;
        pending = null;
        if (result.success()) onClose(); else { status = Component.translatable("wiring.unrealtrainmod." + result.key()); updatePreview(); }
    }
    @Override public void tick() {
        if (minecraft.player == null || minecraft.level == null || !minecraft.level.dimension().equals(dimension)
                || !minecraft.player.isAlive() || !minecraft.player.mayBuild()
                || !minecraft.player.getMainHandItem().is(UnrealTrainMod.TRACK_WRENCH.get())) { onClose(); return; }
        var node = WiringClient.node(original.id());
        if (pending == null && (node == null || node.revision() != original.revision())) {
            minecraft.player.displayClientMessage(Component.translatable("wiring.unrealtrainmod." + (node == null ? "missing" : "changed")), true);
            onClose();
        }
    }
    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(left, top, left + 246, top + 226, 0xE616202C);
        graphics.drawString(font, title, left + 8, top + 10, 0xFFFFFFFF, false);
        for (int i = 0; i < 6; i++) graphics.drawString(font,
                Component.translatable(i < 3 ? "wiring.unrealtrainmod.offset_axis" : "wiring.unrealtrainmod.angle_axis", "XYZ".charAt(i % 3)),
                left + 8, top + 35 + i * 24, 0xFFCCDDDD, false);
        graphics.drawString(font, font.plainSubstrByWidth(status.getString(), 230), left + 8, top + 211, 0xFFFF8888, false);
    }
    @Override public void removed() { WiringClient.preview(null); super.removed(); }
    @Override public boolean isPauseScreen() { return false; }
}
