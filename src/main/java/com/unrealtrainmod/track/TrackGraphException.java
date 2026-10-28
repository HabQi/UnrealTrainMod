/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.track;

/** Domain failures are independent of Minecraft's command and translation APIs. */
public final class TrackGraphException extends IllegalArgumentException {
    public enum Reason {
        UNKNOWN_NODE, UNKNOWN_SEGMENT, DUPLICATE_ID, SAME_NODE, GAUGE_MISMATCH,
        NODE_CONNECTED, INVALID_POSITION, INVALID_GAUGE, INDEX_RANGE, INDEX_TOO_LARGE
    }

    private final Reason reason;

    public TrackGraphException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
