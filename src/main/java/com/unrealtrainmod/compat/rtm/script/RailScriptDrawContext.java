/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm.script;

import com.mojang.blaze3d.vertex.PoseStack;
import com.unrealtrainmod.track.geometry.TrackGeometry;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.track.mesh.RailMeshAccumulator;
import com.unrealtrainmod.track.mesh.RailTrackMeshBuilder;
import com.unrealtrainmod.track.mesh.RailTrackMeshData;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** One transactional CPU build. Display lists record before chunk ownership is considered. */
final class RailScriptDrawContext extends ScriptMatrixContext {
    private TrackGeometry geometry;
    private final BlockPos start;
    private final int chunkX, chunkZ;
    private final double scale, period;
    private final boolean preview;
    private final TrackVector origin;
    private final RailTrackMeshBuilder.Filter filter;
    private final RailMeshAccumulator output;

    RailScriptDrawContext(TrackGeometry geometry, BlockPos start, int chunkX, int chunkZ,
                          double scale, double period, boolean preview, RailTrackMeshBuilder.Filter filter) {
        super(new PoseStack());
        this.geometry = geometry;
        this.start = start;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.scale = scale;
        this.period = period;
        this.preview = preview;
        this.filter = filter;
        origin = preview ? new TrackVector(0, 0, 0) : new TrackVector((double)chunkX * 16,
                Math.floor(geometry.sample(0).position().y() / 16) * 16, (double)chunkZ * 16);
        output = new RailMeshAccumulator(origin);
    }

    void geometry(TrackGeometry geometry) { this.geometry = geometry; }

    void repeat(List<RailTrackMeshBuilder.Source> sources, double x, double y, double z) {
        if (isCompiling()) {
            var captured = geometry;
            record(context -> {
                var target = (RailScriptDrawContext)context;
                var previous = target.geometry;
                target.geometry = captured;
                try { target.repeat(sources, x, y, z); }
                finally { target.geometry = previous; }
            });
            return;
        }
        var transform = (preview ? new Matrix4f(pose.last().pose()) : localTransform())
                .translate((float)x, (float)y, (float)z);
        if (preview) {
            transform.scale((float)scale);
            for (var source : sources) {
                if (filter.shouldRender(source.part(), 0, 1)) append(source, transform);
            }
        } else {
            var mesh = RailTrackMeshBuilder.build(geometry, sources, chunkX, chunkZ, scale, period, filter);
            var o = mesh.origin();
            transform.translate((float)(o.x() - start.getX()), (float)(o.y() - start.getY()),
                    (float)(o.z() - start.getZ()));
            for (var batch : mesh.batches()) {
                append(new RailTrackMeshBuilder.Source("", batch.texture(), batch.doubleSided(), batch.mesh()), transform);
            }
        }
    }

    void draw(List<RailTrackMeshBuilder.Source> sources) {
        if (isCompiling()) {
            record(context -> ((RailScriptDrawContext)context).draw(sources));
            return;
        }
        var matrix = pose.last().pose();
        if (!preview && (Math.floor((start.getX() + (double)matrix.m30()) / 16) != chunkX
                || Math.floor((start.getZ() + (double)matrix.m32()) / 16) != chunkZ)) return;
        var transform = localTransform().scale((float)scale);
        for (var source : sources) append(source, transform);
    }

    /** Sample once per placement; matrix and draw calls also work while recording a CPU list. */
    void drawAlong(RailScriptMap map, int split, int first, int last, RailScriptMap.Position anchor,
                   List<RailTrackMeshBuilder.Source> sources) {
        var path = map.geometry();
        double height = anchor.posY - RailScriptMap.HEIGHT_OFFSET;
        for (int index = first; index <= last; index++) {
            var frame = path.sample(split == 0 ? 0 : path.getLength() * ((double)index / split));
            var p = frame.position();
            var t = frame.tangent();
            float yaw = (float)Math.toDegrees(Math.atan2(t.x(), t.z()));
            float pitch = (float)Math.toDegrees(Math.atan2(t.y(), Math.hypot(t.x(), t.z())));
            push();
            try {
                translate((float)(p.x() - anchor.posX), (float)(p.y() - height), (float)(p.z() - anchor.posZ));
                rotate(yaw, 0, 1, 0);
                rotate(-pitch, 1, 0, 0);
                draw(sources);
            } finally { pop(); }
        }
    }

    private Matrix4f localTransform() {
        return new Matrix4f().translation((float)(start.getX() - origin.x()),
                (float)(start.getY() - origin.y()), (float)(start.getZ() - origin.z())).mul(pose.last().pose());
    }

    private void append(RailTrackMeshBuilder.Source source, Matrix4f transform) {
        var mesh = source.mesh();
        var normal = transform.normal(new Matrix3f());
        var p = new Vector3f();
        var n = new Vector3f();
        output.append(source.texture(), source.doubleSided(), mesh, (index, vertices) -> {
            int offset = index * 8;
            float[] v = mesh.vertices();
            transform.transformPosition(p.set(v[offset], v[offset + 1], v[offset + 2]));
            normal.transform(n.set(v[offset + 5], v[offset + 6], v[offset + 7]));
            if (n.lengthSquared() > 0) n.normalize();
            vertices.add(p.x); vertices.add(p.y); vertices.add(p.z);
            vertices.add(v[offset + 3]); vertices.add(v[offset + 4]);
            vertices.add(n.x); vertices.add(n.y); vertices.add(n.z);
        });
    }

    RailTrackMeshData finish() { return output.finish(); }

    @Override void brightness(int value) {
        if (isCompiling()) { record(context -> context.brightness(value)); return; }
        ScriptDiagnostics.unsupported("rail script brightness", "cached rail meshes use native per-vertex lighting; previews stay full bright");
    }

    @Override void directionalLighting(boolean value) {
        if (isCompiling()) { record(context -> context.directionalLighting(value)); return; }
        ScriptDiagnostics.unsupported("rail script directional lighting", "cached rail meshes retain native directional lighting");
    }

    @Override void discard() { output.clear(); }
}
