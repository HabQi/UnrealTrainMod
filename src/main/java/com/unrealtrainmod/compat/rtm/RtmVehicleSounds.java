/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.compat.rtm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.unrealtrainmod.resource.PackageArchive;
import com.unrealtrainmod.resource.PackageAsset;
import com.unrealtrainmod.sound.VehicleSoundSequence;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.util.GsonHelper;
import org.slf4j.LoggerFactory;

/** The original NJ01 clips and the reviewed RTM movement program in the owning package. */
public record RtmVehicleSounds(VehicleSoundSequence doorOpen, VehicleSoundSequence doorClose,
                               List<Announcement> announcements, PackageAsset movementScript,
                               Map<String, VehicleSoundSequence> loops) {
    public record Announcement(String name, VehicleSoundSequence sound) {}

    public static final int MOVEMENT_DISTANCE = 64;
    public static final int ANNOUNCEMENT_DISTANCE = 48;
    private static final String NJ01_SCRIPT = "scripts/sound_NJ01A.js";
    private static final String NJ01_HASH = "0bfe9db370fe9d82a0d922700421c516d2c51cbd8ce8a3fce19bcf9233f32e1a";

    static RtmVehicleSounds read(JsonObject json, PackageArchive archive, String prefix, PackageAsset config) {
        try {
            if (!NJ01_SCRIPT.equals(GsonHelper.getAsString(json, "soundScriptPath", ""))) return null;
            // RTM IDs are case-sensitive in the original JSON and ZIP. Keep them as strings until resolved.
            var events = readEvents(archive, prefix);
            var doorOpen = resolve(doorEvent(json, "sound_DoorOpen"), events, archive, prefix, config, false, 32);
            var doorClose = resolve(doorEvent(json, "sound_DoorClose"), events, archive, prefix, config, false, 32);
            var announcements = new ArrayList<Announcement>();
            var entries = json.has("sound_Announcement") ? GsonHelper.getAsJsonArray(json, "sound_Announcement") : new JsonArray();
            for (var entry : entries) {
                var pair = entry.getAsJsonArray();
                if (pair.size() != 2) throw new IOException("Expected an announcement name and event");
                announcements.add(new Announcement(pair.get(0).getAsString(),
                        resolve(pair.get(1).getAsString(), events, archive, prefix, config, false, ANNOUNCEMENT_DISTANCE)));
            }
            var loops = new LinkedHashMap<String, VehicleSoundSequence>();
            PackageAsset script = null;
            try {
                var candidate = archive.resolve(prefix, "minecraft:" + NJ01_SCRIPT);
                try (var stream = archive.open(candidate.entry())) {
                    if (!verifiedScript(stream.readAllBytes())) throw new IOException("Unsupported NJ01 sound script revision");
                }
                script = candidate;
                for (String id : List.of("sounds:train.NJ01A", "sounds:train.NJ01RUN", "sounds:train.NJ01RUN2")) {
                    var sequence = resolve(id, events, archive, prefix, config, true, MOVEMENT_DISTANCE);
                    if (sequence != null) loops.put(id, sequence);
                }
            } catch (IOException exception) {
                LoggerFactory.getLogger(RtmVehicleSounds.class).warn("RTM {}: skipping movement sound: {}", config, exception.getMessage());
            }
            return new RtmVehicleSounds(doorOpen, doorClose, List.copyOf(announcements), script, Map.copyOf(loops));
        } catch (IOException | RuntimeException exception) {
            LoggerFactory.getLogger(RtmVehicleSounds.class).warn("RTM {}: skipping NJ01 sounds: {}", config, exception.getMessage());
            return null;
        }
    }

    public static boolean verifiedScript(byte[] bytes) {
        try {
            return NJ01_HASH.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static JsonObject readEvents(PackageArchive archive, String prefix) throws IOException {
        var asset = archive.resolve(prefix, "sounds:sounds.json");
        try (var reader = new InputStreamReader(archive.open(asset.entry()), StandardCharsets.UTF_8)) {
            return new Gson().fromJson(reader, JsonObject.class);
        }
    }

    private static String doorEvent(JsonObject json, String field) {
        String id = GsonHelper.getAsString(json, field, "");
        return id.equals("rtm:train.door") ? "sounds:train.door" : id;
    }

    private static VehicleSoundSequence resolve(String id, JsonObject events, PackageArchive archive, String prefix,
                                                PackageAsset config, boolean loop, int distance) {
        if (id.isBlank()) return null;
        try {
            if (!id.startsWith("sounds:")) throw new IOException("Unsupported sound namespace");
            String key = id.substring("sounds:".length());
            String clip;
            if (events.has(key)) {
                var choices = GsonHelper.getAsJsonArray(events.getAsJsonObject(key), "sounds");
                if (choices.size() != 1 || !choices.get(0).isJsonPrimitive()) {
                    throw new IOException("Expected one file in the NJ01 sound event");
                }
                clip = choices.get(0).getAsString();
            } else {
                // Three station events are absent from this manifest but have matching OGG entries.
                clip = key.replace('.', '/');
            }
            var asset = archive.resolve(prefix, "sounds:sounds/" + clip + ".ogg");
            return new VehicleSoundSequence(null, asset, null, loop, 1, distance);
        } catch (IOException | RuntimeException exception) {
            LoggerFactory.getLogger(RtmVehicleSounds.class).warn("RTM {}: sound {} unavailable: {}", config, id, exception.getMessage());
            return null;
        }
    }
}
