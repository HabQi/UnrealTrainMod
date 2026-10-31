/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm.script;

import javax.script.Invocable;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory;
import org.openjdk.nashorn.api.scripting.ScriptObjectMirror;

/** Creates and initialises the shared Nashorn environment used by every legacy script entry point. */
public final class RtmScript {
    public static final String VEHICLE_RENDERER = "jp.ngt.rtm.render.VehiclePartsRenderer";
    public static final String RAIL_RENDERER = "jp.ngt.rtm.render.RailPartsRenderer";

    private static final String GLOBALS = String.join("\n",
            "var __UTM_Parts = Java.type('com.unrealtrainmod.compat.rtm.script.Parts');",
            "var Parts = __UTM_Parts;",
            "var __UTM_GL11 = Java.type('com.unrealtrainmod.compat.rtm.script.GL11');",
            "var GL11 = __UTM_GL11;",
            "var __UTM_RailDir = Java.type('com.unrealtrainmod.compat.rtm.script.RailDir');",
            "var RailDir = __UTM_RailDir;");

    private static final String[][] REWRITES = {
            {"Java.type(\"org.lwjgl.opengl.GL11\")", "__UTM_GL11"},
            {"Java.type('org.lwjgl.opengl.GL11')", "__UTM_GL11"},
            {"Packages.org.lwjgl.opengl.GL11", "__UTM_GL11"},
            {"org.lwjgl.opengl.GL11", "__UTM_GL11"},
            {"Packages.jp.ngt.rtm.render.Parts", "__UTM_Parts"},
            {"jp.ngt.rtm.render.Parts", "__UTM_Parts"},
            {"Packages.jp.ngt.rtm.rail.util.RailDir", "__UTM_RailDir"},
            {"jp.ngt.rtm.rail.util.RailDir", "__UTM_RailDir"},
    };

    private RtmScript() {}

    public static ScriptEngine createEngine() {
        var factory = new NashornScriptEngineFactory();
        ScriptEngine engine = factory.getScriptEngine(
                new String[]{"-doe", "--language=es6"}, RtmScript.class.getClassLoader());
        try {
            engine.eval("load(\"nashorn:mozilla_compat.js\")");
            engine.eval(GLOBALS);
        } catch (ScriptException exception) {
            throw new IllegalStateException("Cannot initialise the Nashorn compatibility layer", exception);
        }
        return engine;
    }

    public static void eval(ScriptEngine engine, RtmScriptSource.Prepared prepared) throws ScriptException {
        engine.getContext().setAttribute(ScriptEngine.FILENAME, prepared.primaryFile(), ScriptContext.ENGINE_SCOPE);
        engine.eval(rewrite(prepared.source()));
    }

    public static String describe(RtmScriptSource.Prepared prepared, ScriptException exception) {
        int line = exception.getLineNumber();
        return line > 0 ? prepared.fileOf(line) + ":" + line : prepared.primaryFile();
    }

    public static String renderClass(ScriptEngine engine) {
        Object value = engine.get("renderClass");
        return value == null ? "" : value.toString().strip();
    }

    public static boolean hasFunction(ScriptEngine engine, String name) {
        return engine.get(name) instanceof ScriptObjectMirror mirror && mirror.isFunction();
    }

    public static Object invoke(ScriptEngine engine, String name, Object... arguments)
            throws ScriptException, NoSuchMethodException {
        if (!(engine instanceof Invocable invocable)) throw new ScriptException("Script engine is not invocable");
        return invocable.invokeFunction(name, arguments);
    }

    /** Legacy scripts use both imported short names and a few fully qualified spellings. */
    private static String rewrite(String source) {
        String value = source.replaceAll(
                "(?m)^\\s*importClass\\s*\\(\\s*(?:Packages\\.)?(?:org\\.lwjgl\\.opengl\\.GL11"
                        + "|jp\\.ngt\\.rtm\\.render\\.Parts|jp\\.ngt\\.rtm\\.rail\\.util\\.RailDir)\\s*\\)\\s*;?"
                        + "(?:\\s*//.*)?\\s*$",
                "");
        for (var rewrite : REWRITES) value = value.replace(rewrite[0], rewrite[1]);
        return value;
    }
}
