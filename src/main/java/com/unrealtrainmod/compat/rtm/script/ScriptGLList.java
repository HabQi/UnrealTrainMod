/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm.script;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** CPU commands only; no legacy OpenGL name or GPU resource is allocated. */
public final class ScriptGLList {
    final List<Consumer<ScriptMatrixContext>> commands = new ArrayList<>();
    boolean compiled, deleted;

    void delete() {
        commands.clear();
        compiled = false;
        deleted = true;
    }
}
