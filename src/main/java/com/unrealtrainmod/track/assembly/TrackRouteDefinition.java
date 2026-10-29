/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.assembly;

/** Both directions of one ordinary turnout path share the same required state. */
public record TrackRouteDefinition(String entrance, String exit, TrackAssemblyInstance.State state) {}
