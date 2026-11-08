/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.ir;

/** Logical controls exist even when their objects have no named geometric transform. */
public record IrControlDefinition(String group, String label, Type type, float defaultValue, IrReadout driver) {
    public enum Type { CONTINUOUS, TOGGLE, PRESS }
    public boolean readOnly() { return driver != null; }
}
