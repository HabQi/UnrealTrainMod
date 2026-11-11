/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm.script;

import com.unrealtrainmod.client.track.TrackPartSnapshot;

import com.mojang.blaze3d.vertex.PoseStack;
import com.unrealtrainmod.track.geometry.TrackGeometry;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.track.mesh.RailMeshAccumulator;
import com.unrealtrainmod.track.mesh.RailTrackMeshBuilder;
import com.unrealtrainmod.track.mesh.RailTrackMeshData;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
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
    private List<Consumer<RailScriptDrawContext>> staticCommands;
    private List<TrackPartSnapshot.Part> dynamicParts;
    private TrackPartSnapshot previousDynamic;

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
    double modelScale() { return scale; }

    void captureStatic(List<Consumer<RailScriptDrawContext>> commands) { staticCommands = commands; }
    void captureDynamic(TrackPartSnapshot previous) { dynamicParts = new ArrayList<>(); previousDynamic = previous; }
    TrackPartSnapshot snapshot() { return new TrackPartSnapshot(dynamicParts); }

    private void replay(Matrix4f matrix, Runnable command) {
        push();
        try {
            pose.last().pose().set(matrix);
            matrix.normal(pose.last().normal());
            command.run();
        } finally { pop(); }
    }

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
        if (staticCommands != null) {
            var path = geometry;
            var matrix = new Matrix4f(pose.last().pose());
            if (!matrix.isFinite()) throw new IllegalArgumentException("Non-finite rail script transform");
            long count = (long)Math.ceil(path.getLength() / (scale * period));
            // Evaluate the JS filter now, while its tile/map is bound, never during chunk replay.
            Map<String, boolean[]> selection = new HashMap<>();
            for (var source : sources) {
                if (selection.containsKey(source.part())) continue;
                boolean[] allowed = new boolean[Math.toIntExact(count)];
                for (int i = 0; i < allowed.length; i++) allowed[i] = filter.shouldRender(source.part(), i, count);
                selection.put(source.part(), allowed);
            }
            staticCommands.add(target -> {
                target.geometry(path);
                target.replay(matrix, () -> target.repeatSelected(sources, x, y, z,
                        (part, index, total) -> selection.get(part)[Math.toIntExact(index)]));
            });
            return;
        }
        if (dynamicParts != null) throw new IllegalArgumentException("Dynamic renderStaticParts is not a rigid part draw");
        repeatSelected(sources, x, y, z, filter);
    }

    private void repeatSelected(List<RailTrackMeshBuilder.Source> sources, double x, double y, double z,
                                RailTrackMeshBuilder.Filter selected) {
        var transform = (preview ? new Matrix4f(pose.last().pose()) : localTransform())
                .translate((float)x, (float)y, (float)z);
        if (preview) {
            transform.scale((float)scale);
            for (var source : sources) {
                if (selected.shouldRender(source.part(), 0, 1)) append(source, transform);
            }
        } else {
            var mesh = RailTrackMeshBuilder.build(geometry, sources, chunkX, chunkZ, scale, period, selected);
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
        // Decide after display-list replay has applied its transforms, before either snapshot records the draw.
        if (staticCommands != null) {
            var matrix = new Matrix4f(pose.last().pose());
            if (!matrix.isFinite()) throw new IllegalArgumentException("Non-finite rail script transform");
            staticCommands.add(target -> target.replay(matrix, () -> target.draw(sources)));
            return;
        }
        if (dynamicParts != null) {
            var transform = new Matrix4f(pose.last().pose()).scale((float)scale);
            for (var source : sources) {
                int index = dynamicParts.size();
                var previous = previousDynamic != null && index < previousDynamic.parts().size()
                        ? previousDynamic.parts().get(index) : null;
                dynamicParts.add(previous != null && previous.source() == source && previous.transform().equals(transform)
                        ? previous : new TrackPartSnapshot.Part(source, transform));
            }
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
        double height = anchor.posY - RailScriptMap.HEIGHT_OFFSET;
        for (int index = first; index <= last; index++) {
            var frame = map.sample(split, index);
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
        ScriptDiagnostics.unsupported("rail script brightness", "native world lighting is used for vertices and rigid part instances; previews stay full bright");
    }

    @Override void directionalLighting(boolean value) {
        if (isCompiling()) { record(context -> context.directionalLighting(value)); return; }
        ScriptDiagnostics.unsupported("rail script directional lighting", "cached rail meshes retain native directional lighting");
    }

    @Override void discard() { output.clear(); }
}
