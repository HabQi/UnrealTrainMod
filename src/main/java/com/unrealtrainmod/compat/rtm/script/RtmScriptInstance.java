/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm.script;

import java.io.IOException;
import javax.script.Invocable;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import javax.script.SimpleBindings;
import javax.script.SimpleScriptContext;
import org.openjdk.nashorn.api.scripting.ScriptObjectMirror;

/** An isolated global and context for one runtime; no instance is retained by the compiled program. */
final class RtmScriptInstance implements AutoCloseable {
    private RtmScriptProgram program;
    private ScriptContext context;
    private ScriptObjectMirror global;

    RtmScriptInstance(RtmScriptProgram program) throws IOException {
        this.program = program;
        context = new SimpleScriptContext();
        // Nashorn associates a new Global with these bindings and remembers this context for later invokes.
        context.setBindings(new SimpleBindings(), ScriptContext.ENGINE_SCOPE);
        context.setAttribute(ScriptEngine.FILENAME, RtmScriptProgram.BOOTSTRAP_FILE, ScriptContext.ENGINE_SCOPE);
        boolean ready = false;
        try {
            global = (ScriptObjectMirror)program.bootstrap.eval(context);
            context.setAttribute(ScriptEngine.FILENAME, program.prepared.primaryFile(), ScriptContext.ENGINE_SCOPE);
            ready = true;
        } catch (ScriptException | RuntimeException | LinkageError exception) {
            throw RtmScriptProgram.compatibilityFailure("initialisation", exception);
        } finally {
            if (!ready) close();
        }
    }

    void put(String name, Object value) { global.put(name, value); }

    void eval() throws IOException {
        try {
            program.compiled.eval(context);
        } catch (ScriptException | RuntimeException | LinkageError exception) {
            throw program.failure("top-level execution", exception);
        }
    }

    String renderClass() throws IOException {
        try {
            Object value = global.get("renderClass");
            return value == null ? "" : value.toString().strip();
        } catch (RuntimeException | LinkageError exception) {
            throw program.failure("entry check", exception);
        }
    }

    boolean hasFunction(String name) throws IOException {
        try {
            return global.get(name) instanceof ScriptObjectMirror mirror && mirror.isFunction();
        } catch (RuntimeException | LinkageError exception) {
            throw program.failure("entry check", exception);
        }
    }

    Object invoke(String name, Object... arguments) throws ScriptException, NoSuchMethodException {
        return ((Invocable)program.engine).invokeMethod(global, name, arguments);
    }

    void init() throws IOException {
        try {
            invoke("init", null, null);
        } catch (ScriptException | NoSuchMethodException | RuntimeException | LinkageError exception) {
            throw program.failure("init", exception);
        }
    }

    @Override
    public void close() {
        // Global properties can be non-configurable. Drop ownership rather than executing script or deleting them.
        global = null;
        context = null;
        program = null;
    }
}
