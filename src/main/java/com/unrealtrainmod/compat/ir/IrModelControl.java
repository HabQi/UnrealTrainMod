/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.ir;

/** Authored component identity is separate from its possibly shared logical control group. */
public record IrModelControl(String group, IrControlDefinition.Type type, boolean interactive) {}
