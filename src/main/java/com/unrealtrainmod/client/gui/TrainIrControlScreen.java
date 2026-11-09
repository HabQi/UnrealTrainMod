/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.gui;

import com.unrealtrainmod.client.TrainDrivingClient;
import com.unrealtrainmod.client.TrainCursorClient;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

/** Transparent input owner for the HUD panel; the normal driving overlay still draws the desk. */
public final class TrainIrControlScreen extends Screen {
    private final Set<Integer> pressed = new HashSet<>();
    public TrainIrControlScreen() { super(TrainIrControlPanel.text("title")); }

    @Override protected void init() { addWidget(TrainIrControlPanel.searchField()); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}

    public boolean searching() { return TrainIrControlPanel.searching(); }

    /** Releasing the cursor key ends input ownership without collapsing the floating panel. */
    public void closeInput() {
        TrainCursorClient.release();
        TrainIrControlPanel.stopSearching();
        if (minecraft.screen != this) return;
        if (TrainDrivingClient.state() == null) {
            // World teardown can forbid setScreen(null) while firing its unload callbacks.
            minecraft.tell(() -> { if (minecraft.screen == this) minecraft.setScreen(null); });
        } else minecraft.setScreen(null);
    }

    @Override public void removed() {
        TrainIrControlPanel.stopSearching();
        TrainCursorClient.release();
    }

    @Override public void onClose() {
        if (TrainIrControlPanel.expanded()) TrainIrControlPanel.toggle();
        else closeInput();
        TrainCursorClient.update();
    }

    @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
        boolean first = pressed.add(key);
        if (TrainCursorClient.key(key, scanCode, true)) return true;
        if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            if (!first) return true;
            if (searching()) {
                setFocused(null);
                TrainIrControlPanel.stopSearching();
                TrainCursorClient.update();
            } else if (key == GLFW.GLFW_KEY_ESCAPE) onClose();
            return true;
        }
        if (searching()) return super.keyPressed(key, scanCode, modifiers);
        if (first) TrainDrivingClient.controlKey(key, scanCode);
        return true;
    }

    @Override public boolean keyReleased(int key, int scanCode, int modifiers) {
        pressed.remove(key);
        if (TrainCursorClient.key(key, scanCode, false)) return true;
        return super.keyReleased(key, scanCode, modifiers);
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (TrainCursorClient.mouseKey(button, true)) return true;
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return true;
        if (super.mouseClicked(x, y, button)) return true;
        setFocused(null);
        TrainIrControlPanel.stopSearching();
        TrainCursorClient.click(x, y);
        return true;
    }

    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            TrainCursorClient.drag(x, y);
        }
        return true;
    }

    @Override public boolean mouseReleased(double x, double y, int button) {
        if (TrainCursorClient.mouseKey(button, false)) return true;
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            TrainCursorClient.drag(x, y);
            TrainCursorClient.release();
        }
        super.mouseReleased(x, y, button);
        TrainCursorClient.update();
        return true;
    }

    @Override public boolean mouseScrolled(double x, double y, double dx, double dy) {
        TrainCursorClient.scroll(x, y, dy);
        return true;
    }
}
