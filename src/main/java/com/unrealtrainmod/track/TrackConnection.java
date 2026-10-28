/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track;

import java.util.UUID;

/** fromStart means entering at the segment's start and travelling toward its end. */
public record TrackConnection(UUID segmentId, boolean fromStart) {}
