/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.ir;

import com.unrealtrainmod.resource.PackageAsset;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Shared immutable SRT frames. Aliases reference tracks rather than duplicating their arrays. */
public final class IrAnimatrix {
    public record Frame(float x, float y, float z, float qx, float qy, float qz, float qw,
                        float sx, float sy, float sz) {
        public Quaternionf rotation() { return new Quaternionf(qx, qy, qz, qw); }
    }
    public record Track(List<Frame> frames) {
        public Track { frames = List.copyOf(frames); }
    }
    private final List<Track> tracks;
    private final Map<String, Integer> groups;
    private final int frameCount;

    public IrAnimatrix(List<Track> tracks, Map<String, Integer> groups) {
        this.tracks = List.copyOf(tracks);
        this.groups = Map.copyOf(groups);
        frameCount = groups.values().stream().mapToInt(i -> tracks.get(i).frames().size()).max().orElseThrow();
    }

    public List<Track> tracks() { return tracks; }
    public Map<String, Integer> groups() { return groups; }
    public int frameCount() { return frameCount; }

    /** Lightweight timing scan for sounds: no numeric matrices, SRT frames or model geometry. */
    public static int countFrames(PackageAsset asset) throws IOException {
        var counts = new HashMap<String, Integer>();
        var names = new ArrayList<String>();
        int frames = 0;
        try (var reader = new BufferedReader(new InputStreamReader(asset.open(), StandardCharsets.UTF_8.newDecoder()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                if (line.startsWith("O ")) {
                    if (!names.isEmpty() && frames == 0) throw new IOException("Animation track contains no frames: " + asset);
                    for (String name : names) counts.put(name, frames);
                    names.clear(); names.add(line.substring(2)); frames = 0;
                } else if (line.startsWith("A ") && !names.isEmpty()) names.add(line.substring(2));
                else if (line.startsWith("M ") && !names.isEmpty()) frames++;
                else throw new IOException("Invalid animation directive: " + asset);
            }
        }
        if (!names.isEmpty() && frames == 0) throw new IOException("Animation track contains no frames: " + asset);
        for (String name : names) counts.put(name, frames);
        if (counts.isEmpty()) throw new IOException("Animation contains no frames: " + asset);
        return counts.values().stream().mapToInt(Integer::intValue).max().orElseThrow();
    }

    public static IrAnimatrix read(PackageAsset asset, float scale) throws IOException {
        var tracks = new ArrayList<Track>();
        var groups = new HashMap<String, Integer>();
        var names = new ArrayList<String>();
        var frames = new ArrayList<Frame>();
        var basis = new Matrix4f(0, 0, -scale, 0, 0, scale, 0, 0, scale, 0, 0, 0, 0, 0, 0, 1);
        var inverse = new Matrix4f(basis).invert();
        try (var reader = new BufferedReader(new InputStreamReader(asset.open(), StandardCharsets.UTF_8.newDecoder()))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) continue;
                if (line.startsWith("O ")) {
                    finish(tracks, groups, names, frames);
                    names = new ArrayList<>(); frames = new ArrayList<>();
                    names.add(line.substring(2));
                } else if (line.startsWith("A ") && !names.isEmpty()) {
                    names.add(line.substring(2));
                } else if (line.startsWith("M ") && !names.isEmpty()) {
                    try {
                        String[] numbers = line.substring(2).split(",");
                        if (numbers.length != 16) throw new IllegalArgumentException("Expected 16 matrix entries");
                        float[] m = new float[16];
                        for (int i = 0; i < 16; i++) {
                            m[i] = Float.parseFloat(numbers[i]);
                            if (!Float.isFinite(m[i])) throw new IllegalArgumentException("Non-finite matrix");
                        }
                        if (m[12] != 0 || m[13] != 0 || m[14] != 0 || m[15] != 1) {
                            throw new IllegalArgumentException("Expected an affine matrix");
                        }
                        // The file is row-major; JOML's constructor enumerates columns.
                        var raw = new Matrix4f(m[0], m[4], m[8], m[12], m[1], m[5], m[9], m[13],
                                m[2], m[6], m[10], m[14], m[3], m[7], m[11], m[15]);
                        frames.add(decompose(new Matrix4f(basis).mul(raw).mul(inverse)));
                    } catch (IllegalArgumentException exception) {
                        throw new IOException(asset + ":" + lineNumber + ": " + exception.getMessage(), exception);
                    }
                } else throw new IOException(asset + ":" + lineNumber + ": invalid animation directive");
            }
        }
        finish(tracks, groups, names, frames);
        if (groups.isEmpty()) throw new IOException("Animation contains no frames: " + asset);
        return new IrAnimatrix(tracks, groups);
    }

    private static void finish(List<Track> tracks, Map<String, Integer> groups, List<String> names, List<Frame> frames) throws IOException {
        if (names.isEmpty()) return;
        if (frames.isEmpty()) throw new IOException("Animation track contains no frames: " + names.getFirst());
        int index = tracks.size();
        tracks.add(new Track(frames));
        for (String name : names) groups.put(name, index);
    }

    private static Frame decompose(Matrix4f matrix) {
        var position = matrix.getTranslation(new Vector3f());
        var scale = matrix.getScale(new Vector3f());
        if (Math.min(scale.x, Math.min(scale.y, scale.z)) <= 0) throw new IllegalArgumentException("Singular animation scale");
        if (matrix.determinant3x3() < 0) scale.x = -scale.x;
        var rotationMatrix = new Matrix4f(matrix).scale(1 / scale.x, 1 / scale.y, 1 / scale.z);
        // This implementation supports SRT frames, including non-uniform and signed scales, not shear.
        var x = rotationMatrix.getColumn(0, new Vector3f());
        var y = rotationMatrix.getColumn(1, new Vector3f());
        var z = rotationMatrix.getColumn(2, new Vector3f());
        if (Math.max(Math.abs(x.dot(y)), Math.max(Math.abs(x.dot(z)), Math.abs(y.dot(z)))) > 1e-4f) {
            throw new IllegalArgumentException("Sheared animation frame is unsupported");
        }
        var q = rotationMatrix.getNormalizedRotation(new Quaternionf()).normalize();
        return new Frame(position.x, position.y, position.z, q.x, q.y, q.z, q.w, scale.x, scale.y, scale.z);
    }

    public Frame sample(int index, float percent, boolean looping) {
        var frames = tracks.get(index).frames();
        int count = frames.size();
        if (!looping) {
            if (percent <= 0) return frames.getFirst();
            if (percent >= (count - 1f) / count) return frames.getLast();
        }
        double frame = ((percent % 1 + 1) % 1) * count;
        var a = frames.get((int)Math.floor(frame) % count);
        var b = frames.get((int)Math.ceil(frame) % count);
        float t = (float)(frame - Math.floor(frame));
        if (a.equals(b) || t == 0) return a;
        var q = a.rotation().slerp(b.rotation(), t);
        return new Frame(lerp(a.x, b.x, t), lerp(a.y, b.y, t), lerp(a.z, b.z, t), q.x, q.y, q.z, q.w,
                lerp(a.sx, b.sx, t), lerp(a.sy, b.sy, t), lerp(a.sz, b.sz, t));
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }
}
