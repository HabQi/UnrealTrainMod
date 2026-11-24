/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.blueprint;

import com.unrealtrainmod.blueprint.BlueprintDefinition;
import com.unrealtrainmod.blueprint.BlueprintPayloads;
import com.unrealtrainmod.blueprint.BlueprintPayloads.Action;
import com.unrealtrainmod.item.BlueprintItem;
import com.unrealtrainmod.item.BlueprintPlacerItem;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

final class BlueprintPlacementScreen extends Screen {
    private static final int PANEL_WIDTH = 302, PANEL_HEIGHT = 224;
    private final ItemStack blueprint;
    private final BlueprintDefinition definition;
    private int left, top;
    private EditBox spacing;
    private Button apply, section, direction, line, undo, clear, pick, preview, build, cancel;
    private boolean startB, reverse, closeAfterApply;
    private UUID pending;
    private Action pendingAction;
    private Component feedback = Component.empty();

    BlueprintPlacementScreen(ItemStack blueprint) {
        super(Component.translatable("blueprint.placer.title"));
        this.blueprint = blueprint;
        definition = BlueprintItem.definition(blueprint);
    }

    @Override
    protected void init() {
        String previousSpacing = spacing == null ? null : spacing.getValue();
        left = (width - PANEL_WIDTH) / 2;
        top = Math.max(0, (height - PANEL_HEIGHT) / 2);
        spacing = new EditBox(font, left + 122, top + 35, 65, 18, label("spacing"));
        spacing.setMaxLength(12);
        spacing.setTooltip(Tooltip.create(BlueprintClient.feedback("spacing")));
        addRenderableWidget(spacing);
        apply = button(192, 34, 100, "apply", () -> sendOptions(Action.OPTIONS));
        section = button(10, 58, 136, "start_a", () -> { startB = !startB; updateButtons(); });
        direction = button(154, 58, 138, "forward", () -> { reverse = !reverse; updateButtons(); });
        line = button(10, 82, 136, "next_line", () -> {
            var state = BlueprintClient.state();
            if (state != null) sendLine(Action.SELECT_LINE, (state.getInt("Line") + 1) % definition.tracks().size());
        });
        undo = button(154, 82, 67, "undo", () -> editLine(Action.UNDO_POINT));
        clear = button(225, 82, 67, "clear", () -> editLine(Action.CLEAR_LINE));
        pick = button(10, 128, 136, "world", this::returnToWorld);
        preview = button(154, 128, 138, "preview", () -> sendOptions(Action.PREVIEW));
        build = button(154, 194, 138, "build", this::build);
        cancel = button(10, 194, 136, "cancel", () -> send(Action.CANCEL, new CompoundTag()));
        if (previousSpacing == null) readOptions();
        else spacing.setValue(previousSpacing);
        spacing.setResponder(value -> updateButtons());
        updateButtons();
    }

    private Button button(int x, int y, int width, String key, Runnable action) {
        return addRenderableWidget(Button.builder(label(key), b -> action.run()).bounds(left + x, top + y, width, 20).build());
    }

    private static Component label(String key) { return Component.translatable("blueprint.placer." + key); }

    void refresh() { send(Action.STATUS, new CompoundTag()); }

    boolean isWaitingFor(UUID request) { return request.equals(pending); }

    private void send(Action action, CompoundTag data) {
        if (pending != null || BlueprintClient.blueprint() != blueprint) return;
        if (action != Action.STATUS && BlueprintClient.state() == null) return;
        feedback = Component.empty();
        pendingAction = action;
        pending = BlueprintClient.placementCommand(action, data);
        updateButtons();
    }

    private void sendLine(Action action, int index) {
        var data = new CompoundTag();
        data.putInt("Line", index);
        send(action, data);
    }

    private void editLine(Action action) {
        var state = BlueprintClient.state();
        if (state != null) sendLine(action, state.getInt("Line"));
    }

    private double spacingValue() {
        try { return Double.parseDouble(spacing.getValue()); }
        catch (NumberFormatException e) { return Double.NaN; }
    }

    private void sendOptions(Action action) {
        double value = spacingValue();
        if (!Double.isFinite(value) || value < .01 || value > 256) {
            feedback = BlueprintClient.feedback("spacing");
            closeAfterApply = false;
            return;
        }
        var data = new CompoundTag();
        data.putDouble("Spacing", value);
        data.putBoolean("StartB", startB);
        data.putBoolean("Reverse", reverse);
        send(action, data);
    }

    private boolean optionsChanged(CompoundTag state) {
        return state != null && (spacingValue() != state.getDouble("Spacing")
                || startB != state.getBoolean("StartB") || reverse != state.getBoolean("Reverse"));
    }

    private void readOptions() {
        var state = BlueprintClient.state();
        startB = state != null && state.getBoolean("StartB");
        reverse = state != null && state.getBoolean("Reverse");
        spacing.setValue(state == null ? "32" : Double.toString(state.getDouble("Spacing")));
    }

    private void returnToWorld() {
        var state = BlueprintClient.state();
        if (state != null && !state.getBoolean("Building") && optionsChanged(state)) {
            closeAfterApply = true;
            sendOptions(Action.OPTIONS);
        } else onClose();
    }

    private void build() {
        var state = BlueprintClient.state();
        if (!BlueprintClient.ready() || optionsChanged(state)) return;
        var data = new CompoundTag();
        data.putUUID("Token", state.getUUID("Token"));
        send(Action.BUILD, data);
    }

    void acceptState(BlueprintPayloads.Message message) {
        if (!message.key().isEmpty()) feedback = BlueprintClient.feedback(message.key());
        if (isWaitingFor(message.request())) {
            var action = pendingAction;
            pending = null;
            pendingAction = null;
            if (action == Action.STATUS || action == Action.OPTIONS && message.key().isEmpty()) readOptions();
            if (action == Action.OPTIONS && closeAfterApply && message.key().isEmpty()
                    || action == Action.PREVIEW && message.key().equals("ready") && BlueprintClient.ready()) onClose();
            closeAfterApply = false;
        }
        updateButtons();
    }

    void acceptResult(BlueprintPayloads.Message message) {
        boolean refresh = pendingAction != Action.STATUS && message.key().equals("changed");
        pending = null;
        pendingAction = null;
        closeAfterApply = false;
        if (refresh) {
            BlueprintClient.clearSession();
            refresh();
        }
        feedback = BlueprintClient.feedback(message.key());
        updateButtons();
    }

    private void updateButtons() {
        var state = BlueprintClient.state();
        boolean hasState = state != null;
        boolean building = hasState && state.getBoolean("Building");
        boolean available = hasState && pending == null;
        boolean editing = available && !building;
        boolean changed = optionsChanged(state);
        int points = hasState ? state.getIntArray("Points")[state.getInt("Line")] : 0;
        spacing.setEditable(editing);
        apply.active = editing && changed;
        section.active = editing && definition.alternating();
        section.setMessage(label(startB ? "start_b" : "start_a"));
        direction.active = editing;
        direction.setMessage(label(reverse ? "reverse" : "forward"));
        line.active = editing && definition.tracks().size() > 1;
        undo.active = clear.active = editing && points > 0;
        pick.active = pending == null;
        preview.active = editing;
        build.active = editing && !changed && BlueprintClient.ready();
        cancel.active = available;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xF018202A);
        graphics.drawString(font, title, left + 10, top + 7, 0xFFFFFF);
        drawClipped(graphics, BlueprintPlacerItem.blueprintName(blueprint), top + 20, 0x9DD1F4);
        graphics.drawString(font, label("spacing"), left + 10, top + 40, 0xE1E8EF);
        var state = BlueprintClient.state();
        if (state != null) {
            int index = state.getInt("Line");
            var pointCounts = state.getIntArray("Points");
            drawClipped(graphics, Component.translatable("blueprint.placer.line_points", index + 1, pointCounts.length,
                    definition.tracks().get(index).name(), pointCounts[index]), top + 108, 0x9DD1F4);
            if (state.getBoolean("Building")) {
                drawClipped(graphics, Component.translatable("blueprint.placer.progress", state.getInt("Completed"),
                        state.getInt("Stations")), top + 154, 0x8AFFAA);
            } else if (state.hasUUID("Token")) {
                drawClipped(graphics, Component.translatable("blueprint.placer.summary",
                        String.format(Locale.ROOT, "%.2f", state.getDouble("Length")), state.getInt("Stations"),
                        state.getInt("Nodes"), state.getInt("Wires")), top + 154, 0xE1E8EF);
            }
        }
        var status = pendingAction == Action.PREVIEW ? label("generating") : feedback;
        graphics.drawWordWrap(font, status, left + 10, top + 167, PANEL_WIDTH - 20, 0xFFDD88);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawClipped(GuiGraphics graphics, Component text, int y, int color) {
        var clipped = font.substrByWidth(text, PANEL_WIDTH - 20);
        graphics.drawString(font, Language.getInstance().getVisualOrder(clipped), left + 10, y, color);
    }

    @Override public void tick() { if (BlueprintClient.blueprint() != blueprint) onClose(); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) { }
}
