/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm.script;

import java.util.ArrayList;
import java.util.Comparator;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import org.openjdk.nashorn.api.scripting.NashornException;
import org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory;
import org.openjdk.nashorn.api.tree.ExpressionTree;
import org.openjdk.nashorn.api.tree.FunctionCallTree;
import org.openjdk.nashorn.api.tree.IdentifierTree;
import org.openjdk.nashorn.api.tree.LiteralTree;
import org.openjdk.nashorn.api.tree.MemberSelectTree;
import org.openjdk.nashorn.api.tree.Parser;
import org.openjdk.nashorn.api.tree.SimpleTreeVisitorES6;
import org.openjdk.nashorn.api.tree.Tree;

/** Engine construction, source rewriting and source-location diagnostics shared by script programs. */
public final class RtmScript {
    public static final String VEHICLE_RENDERER = "jp.ngt.rtm.render.VehiclePartsRenderer";
    public static final String RAIL_RENDERER = "jp.ngt.rtm.render.RailPartsRenderer";

    private RtmScript() {}

    static ScriptEngine createEngine() {
        var factory = new NashornScriptEngineFactory();
        // Keep Nashorn's default multi-global mode; never enable --global-per-engine.
        return factory.getScriptEngine(
                new String[]{"-doe", "--language=es6"}, RtmScript.class.getClassLoader());
    }

    public static String describe(RtmScriptSource.Prepared prepared, Throwable exception) {
        int line;
        String file;
        if (exception instanceof ScriptException script) {
            line = script.getLineNumber();
            file = script.getFileName();
        } else if (exception instanceof NashornException script) {
            line = script.getLineNumber();
            file = script.getFileName();
        } else return prepared.primaryFile();
        if (file != null && !file.equals(prepared.primaryFile())) {
            return line > 0 ? file + ":" + line : file;
        }
        return line > 0 ? prepared.locationOf(line).toString() : prepared.primaryFile();
    }

    /**
     * Nashorn identifies real type accesses, including expressions inside template literals. Literal text,
     * regular expressions and comments remain untouched; replacements keep the original newline count.
     */
    static String rewrite(String source, String file) throws ScriptException {
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
        return RtmScriptTypes.aliasOf(name);
    }
}
