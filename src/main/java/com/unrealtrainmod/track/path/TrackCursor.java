/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track.path;

/**
 * An occurrence in one route and a distance in blocks from that directed segment's entrance.
 * Repeated segments have different indices. Cursors must not be reused with another route,
 * including a reversed or rebuilt route; the sampler validates their range, not their ownership.
 */
public record TrackCursor(int segmentIndex, double distance) {}
