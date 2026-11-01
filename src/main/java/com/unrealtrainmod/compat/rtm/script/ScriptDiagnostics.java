/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm.script;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One-time compatibility warnings so an unsupported call is visible without flooding the log. */
public final class ScriptDiagnostics {
    private static final Logger LOGGER = LoggerFactory.getLogger("UnrealTrainMod RTM scripts");
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private ScriptDiagnostics() {}

    public static void unsupported(String api, String fallback) {
        if (REPORTED.add(api)) LOGGER.warn("RTM script API {} is not replayed; {}", api, fallback);
    }

    /** A registered name that matches no prepared model part. Reported once per model and name. */
    public static void missingPart(String model, String name) {
        String key = "part|" + model + "|" + name;
        if (REPORTED.add(key)) LOGGER.warn("RTM script names part '{}', but model {} has no matching object", name, model);
    }

    /** A configured door action name that matches no prepared model part. */
    public static void missingActionPart(String model, String name) {
        String key = "action|" + model + "|" + name;
        if (REPORTED.add(key)) LOGGER.warn("RTM vehicle action names part '{}', but model {} has no matching object", name, model);
    }

    /** A model with both door actions and a body renderer; the script keeps ownership of all body parts. */
    public static void actionIgnoredForScript(String model) {
        String key = "action-script|" + model;
        if (REPORTED.add(key)) LOGGER.info(
                "Vehicle model {} declares door actions and a body renderer; the script owns the body and configured actions are ignored",
                model);
    }

    public static void clear() { REPORTED.clear(); }
}
