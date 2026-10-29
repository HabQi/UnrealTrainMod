/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.EnumMap;
import net.minecraft.resources.ResourceLocation;

/** Startup counters only; diagnostic details are logged immediately. */
public final class LoadReport {
    private static final Logger LOGGER = LoggerFactory.getLogger(LoadReport.class);
    public enum Kind {
        RTM_VEHICLE("RTM vehicles"), IR_VEHICLE("IR vehicles"), RTM_RAIL("RTM rails"), IR_RAIL("IR rails");

        private final String label;
        Kind(String label) { this.label = label; }
    }

    private static final class Counts {
        int registered, failed, modelFailures, duplicates, filtered, catalogErrors;
    }

    private final EnumMap<Kind, Counts> counts = new EnumMap<>(Kind.class);
    private int rtmArchives, irArchives;

    public LoadReport() {
        for (var kind : Kind.values()) counts.put(kind, new Counts());
    }

    public void registered(Kind kind) { counts.get(kind).registered++; }

    public void filtered(Kind kind) { counts.get(kind).filtered++; }

    public void modelFailed(Kind kind, PackageAsset config, PackageAsset model, Exception exception) {
        counts.get(kind).modelFailures++;
        LOGGER.error("Unable to load {} model {} for {}: {}", kind.label, model, config,
                exception.getMessage(), exception);
    }

    public void failed(Kind kind, PackageAsset config, Exception exception) {
        counts.get(kind).failed++;
        LOGGER.error("Unable to register {} {}!{}: {}", kind.label,
                config.archive(), config.entry(), exception.getMessage(), exception);
    }

    public void duplicate(Kind kind, ResourceLocation id, PackageAsset incoming, PackageAsset retained) {
        counts.get(kind).duplicates++;
        LOGGER.warn("Duplicate {} {}: skipping {}!{}; retained {}!{}", kind.label, id,
                incoming.archive(), incoming.entry(), retained.archive(), retained.entry());
    }

    public void archiveFailed(boolean rtm, Path archive, Exception exception) {
        if (rtm) rtmArchives++;
        else irArchives++;
        LOGGER.error("Unable to read {} package {}", rtm ? "RTM" : "IR", archive, exception);
    }

    public void directoryFailed(Path directory, Exception exception) {
        LOGGER.error("Unable to initialize package directory {}", directory, exception);
    }

    public void catalogFailed(Kind kind, PackageAsset catalog, Exception exception) {
        counts.get(kind).catalogErrors++;
        LOGGER.error("Unable to read {} catalog {}!{}: {}", kind.label,
                catalog.archive(), catalog.entry(), exception.getMessage(), exception);
    }

    public void missingCatalog(Path archive) {
        LOGGER.warn("IR package {} contains no JSON stock or track catalog", archive);
    }

    public void logSummary(Kind kind, Path directory, String details) {
        var value = counts.get(kind);
        String extra = "; " + value.catalogErrors + " catalog errors, " + value.modelFailures
                + " model failures, " + value.filtered + " filtered";
        if (!details.isEmpty()) extra += "; " + details;
        LOGGER.info("Registered {} {} from {}: {} failed configs, {} duplicates, {} unreadable archives{}",
                value.registered, kind.label, directory, value.failed, value.duplicates,
                kind == Kind.IR_VEHICLE || kind == Kind.IR_RAIL ? irArchives : rtmArchives, extra);
    }
}
