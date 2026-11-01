/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm.script;

import java.util.ArrayList;
import java.util.Comparator;
import javax.script.Invocable;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import org.openjdk.nashorn.api.scripting.NashornException;
import org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory;
import org.openjdk.nashorn.api.scripting.ScriptObjectMirror;
import org.openjdk.nashorn.api.tree.ExpressionTree;
import org.openjdk.nashorn.api.tree.FunctionCallTree;
import org.openjdk.nashorn.api.tree.IdentifierTree;
import org.openjdk.nashorn.api.tree.LiteralTree;
import org.openjdk.nashorn.api.tree.MemberSelectTree;
import org.openjdk.nashorn.api.tree.Parser;
import org.openjdk.nashorn.api.tree.SimpleTreeVisitorES6;
import org.openjdk.nashorn.api.tree.Tree;

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

    /** Fully qualified legacy type and the pre-injected alias that implements the same subset. */
    private static final String[][] REWRITES = {
            {"org.lwjgl.opengl.GL11", "__UTM_GL11"},
            {"jp.ngt.rtm.render.Parts", "__UTM_Parts"},
            {"jp.ngt.rtm.rail.util.RailDir", "__UTM_RailDir"},
    };
    private static final String PACKAGES = "Packages.";

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
        engine.eval(rewrite(prepared.source(), prepared.primaryFile()));
    }

    public static String describe(RtmScriptSource.Prepared prepared, ScriptException exception) {
        int line = exception.getLineNumber();
        return line > 0 ? prepared.locationOf(line).toString() : prepared.primaryFile();
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

    /**
     * Nashorn identifies real type accesses, including expressions inside template literals. Literal text,
     * regular expressions and comments remain untouched; replacements keep the original newline count.
     */
    private static String rewrite(String source, String file) throws ScriptException {
        var replacements = new ArrayList<Replacement>();
        try {
            Parser.create("--language=es6").parse(file, source, null).accept(new SimpleTreeVisitorES6<Void, Void>() {
                @Override
                public Void visitFunctionCall(FunctionCallTree node, Void unused) {
                    String name = qualifiedName(node.getFunctionSelect());
                    if (name.equals("importClass") && node.getArguments().size() == 1
                            && aliasOf(node.getArguments().getFirst()) != null) {
                        // Keep an expression in conditional statements and other expression positions.
                        replacements.add(Replacement.of(node, "(void 0)"));
                        return null;
                    }
                    String alias = aliasOf(node);
                    if (alias != null) {
                        replacements.add(Replacement.of(node, alias));
                        return null;
                    }
                    return super.visitFunctionCall(node, unused);
                }

                @Override
                public Void visitMemberSelect(MemberSelectTree node, Void unused) {
                    String alias = aliasOf(qualifiedName(node));
                    if (alias != null) {
                        replacements.add(Replacement.of(node, alias));
                        return null;
                    }
                    return super.visitMemberSelect(node, unused);
                }
            }, null);
        } catch (NashornException exception) {
            var failure = new ScriptException(exception.getMessage(), file,
                    exception.getLineNumber(), exception.getColumnNumber());
            failure.initCause(exception);
            throw failure;
        }
        replacements.sort(Comparator.comparingInt(Replacement::start));
        var out = new StringBuilder(source.length());
        int offset = 0;
        for (var replacement : replacements) {
            out.append(source, offset, replacement.start()).append(replacement.text());
            for (int index = replacement.start(); index < replacement.end(); index++) {
                if (source.charAt(index) == '\n') out.append('\n');
            }
            offset = replacement.end();
        }
        return out.append(source, offset, source.length()).toString();
    }

    private record Replacement(int start, int end, String text) {
        static Replacement of(Tree node, String text) {
            return new Replacement(Math.toIntExact(node.getStartPosition()), Math.toIntExact(node.getEndPosition()), text);
        }
    }

    private static String qualifiedName(ExpressionTree node) {
        if (node instanceof IdentifierTree identifier) return identifier.getName();
        if (node instanceof MemberSelectTree member) {
            String parent = qualifiedName(member.getExpression());
            return parent.isEmpty() ? "" : parent + "." + member.getIdentifier();
        }
        return "";
    }

    private static String aliasOf(ExpressionTree node) {
        if (node instanceof FunctionCallTree call && qualifiedName(call.getFunctionSelect()).equals("Java.type")
                && call.getArguments().size() == 1 && call.getArguments().getFirst() instanceof LiteralTree literal
                && literal.getValue() instanceof String name) {
            return aliasOf(name);
        }
        return aliasOf(qualifiedName(node));
    }

    private static String aliasOf(String name) {
        String qualified = name.startsWith(PACKAGES) ? name.substring(PACKAGES.length()) : name;
        for (var rewrite : REWRITES) if (rewrite[0].equals(qualified)) return rewrite[1];
        return null;
    }
}
