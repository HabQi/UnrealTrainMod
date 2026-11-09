/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.gui.screen;

import com.unrealtrainmod.client.ClientConfig;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.config.ModConfigs;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.ConfigurationScreen.ConfigurationSectionScreen;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class ModConfigScreen extends OptionsSubScreen {
    private final ModConfig modConfig;

    public ModConfigScreen(ModContainer container, Screen parent) {
        super(parent, Minecraft.getInstance().options,
                Component.translatable("unrealtrainmod.configuration.section.unrealtrainmod.client.toml.title"));
        modConfig = ModConfigs.getConfigSet(ModConfig.Type.CLIENT).stream()
                .filter(config -> config.getModId().equals(container.getModId()))
                .findFirst().orElseThrow();
    }

    @Override
    protected void addOptions() {
        for (Category category : Category.values()) {
            Button button = Button.builder(category.title(),
                    pressed -> minecraft.setScreen(new CategoryScreen(this, modConfig, category)))
                    .width(ConfigurationScreen.BIG_BUTTON_WIDTH).build();
            button.active = ClientConfig.SPEC.isLoaded();
            if (!button.active) {
                button.setTooltip(Tooltip.create(ConfigurationScreen.TOOLTIP_CANNOT_EDIT_NOT_LOADED));
            }
            list.addSmall(button, null);
        }
    }

    private enum Category {
        DEBUG_LOGS("debugLogs",
                ClientConfig.LOG_VEHICLE_INSTANCING_STATS,
                ClientConfig.LOG_ITEM_RENDER_STATS,
                ClientConfig.LOG_TRACK_RENDER_STATS),
        PERFORMANCE("performance",
                ClientConfig.SORT_TRANSLUCENT_TRIANGLES,
                ClientConfig.USE_STATIC_TRANSLUCENT_BATCHES,
                ClientConfig.IRIS_GEOMETRY_CACHE_MIB,
                ClientConfig.IRIS_GEOMETRY_CACHE_RETENTION_SECONDS,
                ClientConfig.USE_VEHICLE_INSTANCING,
                ClientConfig.USE_TRACK_RENDER_STATE_CACHE,
                ClientConfig.GROUP_TRACK_DRAWS_BY_MATERIAL),
        GENERAL("general",
                ClientConfig.SHOW_ACTIVATION_WARNING,
                ClientConfig.TURNOUT_ANIMATION_DURATION_SECONDS);

        private final String translationKey;
        private final Set<ModConfigSpec.ConfigValue<?>> values;

        Category(String name, ModConfigSpec.ConfigValue<?>... values) {
            translationKey = "unrealtrainmod.configuration.category." + name;
            this.values = Set.of(values);
        }

        Component title() {
            return Component.translatable(translationKey);
        }
    }

    private static final class CategoryScreen extends ConfigurationSectionScreen {
        CategoryScreen(Screen parent, ModConfig modConfig, Category category) {
            super(categoryContext(parent, modConfig, category), category.title());
        }

        private static Context categoryContext(Screen parent, ModConfig modConfig, Category category) {
            Context root = Context.top(modConfig.getModId(), parent, modConfig, (context, key, element) -> element);
            // Restrict the actual entries so reset and undo also stay within this category.
            var entries = root.entries().stream()
                    .filter(entry -> category.values.contains(entry.getRawValue()))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            return new Context(root.modId(), root.parent(), root.modConfig(), root.modSpec(),
                    entries, root.valueSpecs(), root.keylist(), root.filter());
        }
    }
}
