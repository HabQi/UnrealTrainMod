/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.assembly;

/** Server-only passage; its persisted protection representative may transfer between remaining consists. */
public record TrailingPass(String pathId, JunctionState returnState) {}
