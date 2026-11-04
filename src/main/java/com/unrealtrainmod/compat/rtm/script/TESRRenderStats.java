/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm.script;

/** Legacy instrumentation hook. Native cached-track statistics own timing and counters. */
public final class TESRRenderStats {
    private TESRRenderStats() {}
    public static void recordRailStage(Object tile, String stage, long nanos) {}
}
