/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.model;

import java.util.List;

/** Source-local geometry for one action node, with diagnostics deferred until the action is rendered. */
public record ActionSelection(PartSelection selection, List<String> missingNames) {
    public ActionSelection {
        missingNames = List.copyOf(missingNames);
    }
}
