/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.ir;

import com.unrealtrainmod.resource.PackageAsset;
import com.unrealtrainmod.track.geometry.TrackVector;

/** An original component key selects this binding, even when its logical control group was renamed. */
public record IrControlSound(String group, IrReadout driver, TrackVector position,
                              PackageAsset engage, PackageAsset move, PackageAsset disengage, Float movePercent) {}
