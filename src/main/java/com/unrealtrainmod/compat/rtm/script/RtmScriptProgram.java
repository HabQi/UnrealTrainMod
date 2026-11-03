/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm.script;

import java.io.IOException;
import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptEngine;
import javax.script.ScriptException;

/** Compiled code owned by one cache domain. Resource code is never evaluated in the engine's default global. */
final class RtmScriptProgram {
    static final String BOOTSTRAP_FILE = "utm:rtm-compat";

    final RtmScriptSource.Prepared prepared;
    final ScriptEngine engine;
    final CompiledScript bootstrap;
    final CompiledScript compiled;
    private final String rewrittenSource;

    RtmScriptProgram(RtmScriptSource.Prepared prepared) throws IOException {
        this.prepared = prepared;
        try {
            rewrittenSource = RtmScript.rewrite(prepared.source(), prepared.primaryFile());
        } catch (ScriptException | RuntimeException | LinkageError exception) {
            throw failure("parse", exception);
        }
        try {
            engine = RtmScript.createEngine();
        } catch (RuntimeException | LinkageError exception) {
            throw new IOException("Engine initialisation failed: " + exception.getMessage(), exception);
        }
        var compiler = (Compilable)engine;
        engine.put(ScriptEngine.FILENAME, BOOTSTRAP_FILE);
        try {
            bootstrap = compiler.compile("load('nashorn:mozilla_compat.js');\n" + RtmScriptTypes.globals() + "\nthis;");
        } catch (ScriptException | RuntimeException | LinkageError exception) {
            throw compatibilityFailure("compilation", exception);
        }
        engine.put(ScriptEngine.FILENAME, prepared.primaryFile());
        try {
            compiled = compiler.compile(rewrittenSource);
        } catch (ScriptException | RuntimeException | LinkageError exception) {
            throw failure("compile", exception);
        }
    }

    IOException failure(String phase, Throwable exception) {
        return new IOException("Script " + phase + " failed at " + RtmScript.describe(prepared, exception)
                + ": " + exception.getMessage(), exception);
    }

    static IOException compatibilityFailure(String phase, Throwable exception) {
        String location = BOOTSTRAP_FILE;
        if (exception instanceof ScriptException script) {
            if (script.getFileName() != null) location = script.getFileName();
            if (script.getLineNumber() > 0) location += ":" + script.getLineNumber();
        }
        return new IOException("Script compatibility " + phase + " failed at " + location
                + ": " + exception.getMessage(), exception);
    }
}
