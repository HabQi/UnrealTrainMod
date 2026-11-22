/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.unrealtrainmod.model.ModelData.Position;
import com.unrealtrainmod.resource.PackageArchive;
import com.unrealtrainmod.resource.PackageAsset;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.GsonHelper;
import org.slf4j.LoggerFactory;

/** RTM destination texture and its body-local display quads. */
public record RtmRollsign(PackageAsset texture, List<String> names, List<Quad> quads) {
    public record Quad(List<Position> corners, float u0, float u1, float v0, float v1, boolean fullBright) {}

    public int frame(int index) { return index >= 0 && index < names.size() ? index : 0; }

    static RtmRollsign read(JsonObject json, PackageArchive archive, String prefix, PackageAsset config) {
        if (!json.has("rollsignTexture") && !json.has("rollsignNames") && !json.has("rollsigns")) return null;
        try {
            var texture = archive.resolve(prefix, GsonHelper.getAsString(json, "rollsignTexture"));
            var entries = GsonHelper.getAsJsonArray(json, "rollsignNames");
            if (entries.size() == 0 || entries.size() > 128) throw new IOException("Expected 1 to 128 rollsign names");
            var names = new ArrayList<String>(entries.size());
            for (var entry : entries) names.add(entry.getAsString());
            var quads = new ArrayList<Quad>();
            for (var element : GsonHelper.getAsJsonArray(json, "rollsigns")) {
                var panel = element.getAsJsonObject();
                float[] uv = coordinates(GsonHelper.getAsJsonArray(panel, "uv"), 4);
                boolean fullBright = !GsonHelper.getAsBoolean(panel, "disableLighting", false);
                for (var surface : GsonHelper.getAsJsonArray(panel, "pos")) {
                    var points = surface.getAsJsonArray();
                    if (points.size() != 4) throw new IOException("Expected four rollsign corners");
                    var corners = new ArrayList<Position>(4);
                    for (var point : points) {
                        float[] xyz = coordinates(point.getAsJsonArray(), 3);
                        corners.add(new Position(xyz[0], xyz[1], xyz[2]));
                    }
                    quads.add(new Quad(List.copyOf(corners), uv[0], uv[1], uv[2], uv[3], fullBright));
                }
            }
            if (quads.isEmpty()) throw new IOException("Expected at least one rollsign surface");
            return new RtmRollsign(texture, List.copyOf(names), List.copyOf(quads));
        } catch (IOException | RuntimeException exception) {
            LoggerFactory.getLogger(RtmRollsign.class).warn("RTM {}: ignoring rollsign: {}", config, exception.getMessage());
            return null;
        }
    }

    private static float[] coordinates(JsonArray array, int length) throws IOException {
        if (array.size() != length) throw new IOException("Expected " + length + " rollsign coordinates");
        var values = new float[length];
        for (int i = 0; i < length; i++) {
            values[i] = array.get(i).getAsFloat();
            if (!Float.isFinite(values[i])) throw new IOException("Non-finite rollsign coordinate");
        }
        return values;
    }
}
