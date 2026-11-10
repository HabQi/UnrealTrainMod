/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.mixin;

import com.unrealtrainmod.client.ChineseFontPolicy;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(OptionInstance.class)
public abstract class ChineseFontOptionMixin {
    @ModifyVariable(method = "set(Ljava/lang/Object;)V", at = @At("HEAD"), argsOnly = true)
    private Object unrealtrainmod$keepUnicodeEnabled(Object value) {
        if (!Boolean.FALSE.equals(value)) return value;
        var options = Minecraft.getInstance().options;
        // Minecraft.options is assigned only after the Options constructor has loaded its settings.
        if (options != null && (Object)this == options.forceUnicodeFont()
                && ChineseFontPolicy.isChinese(options.languageCode)) return Boolean.TRUE;
        return value;
    }

    @Inject(method = "createButton(Lnet/minecraft/client/Options;IIILjava/util/function/Consumer;)"
            + "Lnet/minecraft/client/gui/components/AbstractWidget;", at = @At("RETURN"))
    private void unrealtrainmod$lockChineseFontButton(Options options, int x, int y, int width,
                                                     Consumer<?> onValueChanged,
                                                     CallbackInfoReturnable<AbstractWidget> callback) {
        if ((Object)this == options.forceUnicodeFont() && ChineseFontPolicy.isChinese(options.languageCode)) {
            var button = callback.getReturnValue();
            button.active = false;
            button.setTooltip(Tooltip.create(Component.translatable("gui.unrealtrainmod.font.chinese_unicode")));
        }
    }
}
