/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.assembly;

/** Server-only passage, owned by the assembly's persisted occupant. */
public record TrailingPass(String pathId, JunctionState returnState) {}
