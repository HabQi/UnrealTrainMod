/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.sound;

import com.unrealtrainmod.resource.PackageAsset;

/** Package-local clips and authored playback parameters; safe to load on either logical side. */
public record VehicleSoundSequence(PackageAsset start, PackageAsset main, PackageAsset stop,
                                   boolean looping, float volume, int distance) {}
