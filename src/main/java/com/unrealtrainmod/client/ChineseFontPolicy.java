/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client;

import net.minecraft.client.Options;

/** Chinese language selections use the game's global Unicode option. */
public final class ChineseFontPolicy {
    private ChineseFontPolicy() {}

    public static boolean isChinese(String languageCode) {
        return languageCode.startsWith("zh_") || languageCode.equals("lzh");
    }

    public static void apply(Options options) {
        if (isChinese(options.languageCode)) options.forceUnicodeFont().set(true);
    }
}
