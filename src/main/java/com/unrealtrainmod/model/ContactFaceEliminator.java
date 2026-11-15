/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.model;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.IntPredicate;

/** Load-time subtraction of opaque rectangular contacts, isolated to each original source part. */
public final class ContactFaceEliminator {
    public static final int VERSION = 1;
    private static final int MAX_FRAGMENTS = 256;

    public record Result(ModelData model, int candidateFaces, int removedFaces, int trimmedFaces,
                         int fragmentLimitFaces, int retainedParts) {}

    private record Plane(int axis, float coordinate) {
        int u() { return axis == 0 ? 1 : 0; }
        int v() { return axis == 2 ? 1 : 2; }
    }

    private record Rect(double x0, double y0, double x1, double y1) {
        boolean overlaps(Rect other) {
            return x0 < other.x1 && other.x0 < x1 && y0 < other.y1 && other.y0 < y1;
        }
    }

    private static final class Candidate {
        final int face;
        final Plane plane;
        final Rect bounds;
        final float[] normal;
        final boolean positive;
        final List<Candidate> contacts = new ArrayList<>();

        Candidate(int face, Plane plane, Rect bounds, float[] normal) {
            this.face = face;
            this.plane = plane;
            this.bounds = bounds;
            this.normal = normal;
            positive = normal[plane.axis()] > 0;
        }
    }

    /** Projected position and unrounded corner attributes; clipping preserves the original triangle field. */
    private record Corner(double x, double y, double u, double v,
                          double alpha, double red, double green, double blue) {
        Corner interpolate(Corner other, double t, int axis, double boundary) {
            return new Corner(axis == 0 ? boundary : lerp(x, other.x, t),
                    axis == 1 ? boundary : lerp(y, other.y, t),
                    lerp(u, other.u, t), lerp(v, other.v, t),
                    lerp(alpha, other.alpha, t), lerp(red, other.red, t),
                    lerp(green, other.green, t), lerp(blue, other.blue, t));
        }

        double coordinate(int axis) { return axis == 0 ? x : y; }

        int color() {
            return (int)Math.round(alpha) << 24 | (int)Math.round(red) << 16
                    | (int)Math.round(green) << 8 | (int)Math.round(blue);
        }
    }

    private ContactFaceEliminator() {}

    public static Result eliminate(ModelData model, IntPredicate opaqueMaterial) throws IOException {
        var parts = new ArrayList<ModelData.Part>(model.parts().size());
        int candidates = 0, removed = 0, trimmed = 0, limited = 0, retained = 0;
        for (var part : model.parts()) {
            var groups = new LinkedHashMap<Plane, List<Candidate>>();
            for (int f = 0; f < part.faces().size(); f++) {
                var face = part.faces().get(f);
                if (!opaqueMaterial.test(face.material())) continue;
                var candidate = candidate(f, face, part.positions());
                if (candidate == null) continue;
                groups.computeIfAbsent(candidate.plane, ignored -> new ArrayList<>()).add(candidate);
                candidates++;
            }

            var replacements = new ArrayList<List<ModelData.Face>>(Collections.nCopies(part.faces().size(), null));
            var positions = new ArrayList<>(part.positions());
            int partRemoved = 0, partTrimmed = 0, triangleSaving = 0;
            for (var group : groups.values()) {
                findContacts(group);
                for (var source : group) {
                    if (source.contacts.isEmpty()) continue;
                    // Every cover comes from the original geometry, even if its own face is removed later.
                    List<Rect> pieces = new ArrayList<>(List.of(source.bounds));
                    boolean overLimit = false;
                    source.contacts.sort(Comparator.comparingInt(contact -> contact.face));
                    for (var cover : source.contacts) {
                        var next = new ArrayList<Rect>();
                        for (var piece : pieces) subtract(piece, cover.bounds, next);
                        pieces = next;
                        if (pieces.isEmpty()) break;
                        if (pieces.size() > MAX_FRAGMENTS) {
                            merge(pieces);
                            if (pieces.size() > MAX_FRAGMENTS) { overLimit = true; break; }
                        }
                    }
                    if (overLimit) { limited++; continue; }
                    merge(pieces);
                    var face = part.faces().get(source.face);
                    var faces = cut(face, part.positions(), source, pieces, positions);
                    replacements.set(source.face, faces);
                    triangleSaving += 2 - faces.size(); // cut() emits only nondegenerate triangles.
                    if (faces.isEmpty()) partRemoved++;
                    else partTrimmed++;
                }
            }
            if (triangleSaving <= 0) {
                if (partRemoved + partTrimmed > 0) retained++;
                parts.add(part);
                continue;
            }
            var faces = new ArrayList<ModelData.Face>();
            for (int f = 0; f < part.faces().size(); f++) {
                var replacement = replacements.get(f);
                if (replacement == null) faces.add(part.faces().get(f));
                else faces.addAll(replacement);
            }
            parts.add(new ModelData.Part(part.name(), part.visible(), part.smooth(), part.facet(), positions, faces));
            removed += partRemoved;
            trimmed += partTrimmed;
        }
        return new Result(new ModelData(model.materials(), parts), candidates, removed, trimmed, limited, retained);
    }

    private static Candidate candidate(int index, ModelData.Face face, List<ModelData.Position> positions) {
        if (face.indices().length != 4) return null;
        for (int color : face.colors()) if ((color >>> 24) != 255) return null;
        var first = positions.get(face.indices()[0]);
        int axis = -1;
        for (int a = 0; a < 3; a++) {
            boolean flat = true;
            for (int c = 1; c < 4; c++) {
                flat &= coordinate(positions.get(face.indices()[c]), a) == coordinate(first, a);
            }
            if (flat) { axis = a; break; }
        }
        if (axis < 0) return null;
        var plane = new Plane(axis, zero(coordinate(first, axis)));
        double x0 = Double.POSITIVE_INFINITY, y0 = Double.POSITIVE_INFINITY;
        double x1 = Double.NEGATIVE_INFINITY, y1 = Double.NEGATIVE_INFINITY;
        for (int v : face.indices()) {
            var p = positions.get(v);
            double x = coordinate(p, plane.u()), y = coordinate(p, plane.v());
            x0 = Math.min(x0, x); y0 = Math.min(y0, y);
            x1 = Math.max(x1, x); y1 = Math.max(y1, y);
        }
        // Match the triangulator's minimum ear area, so each original rectangle contributes two triangles.
        if ((x1 - x0) * (y1 - y0) <= 1e-12) return null;
        int corners = 0;
        for (int c = 0; c < 4; c++) {
            var p = positions.get(face.indices()[c]);
            var q = positions.get(face.indices()[(c + 1) % 4]);
            double x = coordinate(p, plane.u()), y = coordinate(p, plane.v());
            if ((x != x0 && x != x1) || (y != y0 && y != y1)) return null;
            int bit = 1 << ((x == x1 ? 1 : 0) | (y == y1 ? 2 : 0));
            if ((corners & bit) != 0) return null;
            corners |= bit;
            // Consecutive corners must form a perimeter edge, excluding crossed quad orderings.
            if (x != coordinate(q, plane.u()) && y != coordinate(q, plane.v())) return null;
        }
        float[] normal = ModelMesh.normal(face, positions);
        if (Math.abs(normal[axis]) != 1) return null;
        if (face.normals() != null) {
            for (int c = 0; c < 4; c++) for (int a = 0; a < 3; a++) {
                if (face.normals()[c * 3 + a] != normal[a]) return null;
            }
        }
        return new Candidate(index, plane, new Rect(x0, y0, x1, y1), normal);
    }

    /** Sweep one plane; expired X intervals never enter the Y overlap comparison. */
    private static void findContacts(List<Candidate> group) {
        group.sort(Comparator.comparingDouble((Candidate c) -> c.bounds.x0).thenComparingInt(c -> c.face));
        var active = new ArrayList<Candidate>();
        for (var current : group) {
            active.removeIf(previous -> previous.bounds.x1 <= current.bounds.x0);
            for (var previous : active) {
                if (previous.positive == current.positive || !previous.bounds.overlaps(current.bounds)) continue;
                previous.contacts.add(current);
                current.contacts.add(previous);
            }
            active.add(current);
        }
    }

    private static void subtract(Rect source, Rect cover, List<Rect> output) {
        if (!source.overlaps(cover)) { output.add(source); return; }
        double x0 = Math.max(source.x0, cover.x0), y0 = Math.max(source.y0, cover.y0);
        double x1 = Math.min(source.x1, cover.x1), y1 = Math.min(source.y1, cover.y1);
        if (source.x0 < x0) output.add(new Rect(source.x0, source.y0, x0, source.y1));
        if (x1 < source.x1) output.add(new Rect(x1, source.y0, source.x1, source.y1));
        if (source.y0 < y0) output.add(new Rect(x0, source.y0, x1, y0));
        if (y1 < source.y1) output.add(new Rect(x0, y1, x1, source.y1));
    }

    private static void merge(List<Rect> pieces) {
        int before;
        do {
            before = pieces.size();
            mergeAlong(pieces, true);
            mergeAlong(pieces, false);
        } while (pieces.size() < before);
    }

    private static void mergeAlong(List<Rect> pieces, boolean horizontal) {
        pieces.sort(horizontal
                ? Comparator.comparingDouble(Rect::y0).thenComparingDouble(Rect::y1).thenComparingDouble(Rect::x0)
                : Comparator.comparingDouble(Rect::x0).thenComparingDouble(Rect::x1).thenComparingDouble(Rect::y0));
        int write = 0;
        for (int read = 0; read < pieces.size(); read++) {
            var current = pieces.get(read);
            if (write > 0) {
                var previous = pieces.get(write - 1);
                boolean adjacent = horizontal
                        ? previous.y0 == current.y0 && previous.y1 == current.y1 && previous.x1 == current.x0
                        : previous.x0 == current.x0 && previous.x1 == current.x1 && previous.y1 == current.y0;
                if (adjacent) {
                    pieces.set(write - 1, new Rect(previous.x0, previous.y0, current.x1, current.y1));
                    continue;
                }
            }
            pieces.set(write++, current);
        }
        pieces.subList(write, pieces.size()).clear();
    }

    private static List<ModelData.Face> cut(ModelData.Face face, List<ModelData.Position> originalPositions,
                                           Candidate source, List<Rect> pieces, List<ModelData.Position> positions)
            throws IOException {
        var output = new ArrayList<ModelData.Face>();
        if (pieces.isEmpty()) return output;
        int[] triangles = ModelMesh.triangulate(face, originalPositions, source.normal);
        var corners = new Corner[4];
        for (int c = 0; c < 4; c++) {
            var p = originalPositions.get(face.indices()[c]);
            int color = face.colors()[c];
            corners[c] = new Corner(coordinate(p, source.plane.u()), coordinate(p, source.plane.v()),
                    face.uv()[c * 2], face.uv()[c * 2 + 1], color >>> 24, color >>> 16 & 255, color >>> 8 & 255, color & 255);
        }
        for (var piece : pieces) for (int t = 0; t < triangles.length; t += 3) {
            List<Corner> polygon = List.of(corners[triangles[t]], corners[triangles[t + 1]], corners[triangles[t + 2]]);
            polygon = clip(polygon, 0, piece.x0, true);
            polygon = clip(polygon, 0, piece.x1, false);
            polygon = clip(polygon, 1, piece.y0, true);
            polygon = clip(polygon, 1, piece.y1, false);
            append(polygon, source, face.material(), positions, output);
        }
        return output;
    }

    private static List<Corner> clip(List<Corner> polygon, int axis, double boundary, boolean minimum) {
        var output = new ArrayList<Corner>();
        if (polygon.isEmpty()) return output;
        var previous = polygon.getLast();
        boolean previousInside = minimum ? previous.coordinate(axis) >= boundary : previous.coordinate(axis) <= boundary;
        for (var current : polygon) {
            boolean inside = minimum ? current.coordinate(axis) >= boundary : current.coordinate(axis) <= boundary;
            if (inside != previousInside) {
                double t = (boundary - previous.coordinate(axis)) / (current.coordinate(axis) - previous.coordinate(axis));
                output.add(previous.interpolate(current, t, axis, boundary));
            }
            if (inside) output.add(current);
            previous = current;
            previousInside = inside;
        }
        return output;
    }

    private static void append(List<Corner> polygon, Candidate source, int material,
                               List<ModelData.Position> positions, List<ModelData.Face> output) {
        // Work at the same float precision as the emitted mesh before discarding duplicate/collinear fan corners.
        for (int c = 1; c < polygon.size() - 1; c++) {
            Corner[] triangle = {polygon.getFirst(), polygon.get(c), polygon.get(c + 1)};
            double ax = (float)triangle[0].x, ay = (float)triangle[0].y;
            double bx = (float)triangle[1].x, by = (float)triangle[1].y;
            double cx = (float)triangle[2].x, cy = (float)triangle[2].y;
            double winding = source.plane.axis() == 1 ? source.normal[1] : -source.normal[source.plane.axis()];
            if (((bx - ax) * (cy - ay) - (by - ay) * (cx - ax)) * winding < 1e-12) continue;
            int[] indices = new int[3], colors = new int[3];
            float[] uv = new float[6], normals = new float[9];
            for (int i = 0; i < 3; i++) {
                var point = triangle[i];
                float x = (float)point.x, y = (float)point.y, plane = source.plane.coordinate();
                var position = switch (source.plane.axis()) {
                    case 0 -> new ModelData.Position(plane, x, y);
                    case 1 -> new ModelData.Position(x, plane, y);
                    default -> new ModelData.Position(x, y, plane);
                };
                indices[i] = positions.size();
                positions.add(position);
                colors[i] = point.color();
                uv[i * 2] = (float)point.u; uv[i * 2 + 1] = (float)point.v;
                System.arraycopy(source.normal, 0, normals, i * 3, 3);
            }
            output.add(new ModelData.Face(indices, uv, colors, material, normals));
        }
    }

    private static float coordinate(ModelData.Position p, int axis) {
        return switch (axis) { case 0 -> p.x(); case 1 -> p.y(); default -> p.z(); };
    }

    private static float zero(float value) { return value == 0 ? 0 : value; }
    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
}
