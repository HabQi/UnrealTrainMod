/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.model;

import com.unrealtrainmod.compat.ir.IrAnimatrix;

/** Playback index is vehicle-local; track frames are shared by resource and scale. */
public record IrAnimationBinding(int animation, IrAnimatrix frames, int track) {}
