/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.resource.cache;

import com.unrealtrainmod.compat.ir.IrModelLoader;
import com.unrealtrainmod.model.IndexedMesh;
import com.unrealtrainmod.model.ModelBounds;
import com.unrealtrainmod.model.ModelData;
import com.unrealtrainmod.model.ModelMesh;
import com.unrealtrainmod.model.ModelTexture;
import com.unrealtrainmod.resource.PackageAsset;
import com.unrealtrainmod.resource.cache.ModelCache.Codec;
import com.unrealtrainmod.resource.cache.ModelCache.Reader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.unrealtrainmod.resource.cache.ModelCache.Reader.require;

/** Lossless, explicit layouts; references are ZIP entry names rebound to the owning package on read. */
public final class ModelCacheCodecs {
    private ModelCacheCodecs() {}

    /** Material names retain diagnostics for unused authored materials; RTM bindings are never cached. */
    public record VehicleGeometry(ModelMesh mesh, List<String> materialNames, Map<String, ModelTexture> textures) {
        public VehicleGeometry {
            materialNames = List.copyOf(materialNames);
            textures = Map.copyOf(textures);
        }
    }

    public static final Codec<ModelBounds> BOUNDS = codec((input, asset) -> bounds(input), ModelCacheCodecs::bounds);
    public static final Codec<Map<String, ModelBounds>> PART_BOUNDS = codec((input, asset) -> {
        int count = input.count(28);
        var result = new HashMap<String, ModelBounds>();
        for (int i = 0; i < count; i++) {
            require(result.put(input.text(), bounds(input)) == null, "Duplicate bounds part");
        }
        return Map.copyOf(result);
    }, (output, value) -> {
        output.writeInt(value.size());
        for (var entry : new TreeMap<>(value).entrySet()) {
            text(output, entry.getKey());
            bounds(output, entry.getValue());
        }
    });
    public static final Codec<ModelData> MODEL_DATA = codec((input, asset) -> model(input), ModelCacheCodecs::model);
    public static final Codec<IrModelLoader.Imported> IR_SOURCE = codec((input, asset) ->
            new IrModelLoader.Imported(model(input), textures(input, asset)), (output, value) -> {
        model(output, value.model());
        textures(output, value.textures());
    });
    public static final Codec<ModelMesh> MESH = codec((input, asset) -> mesh(input), ModelCacheCodecs::mesh);
    public static final Codec<VehicleGeometry> VEHICLE_GEOMETRY = codec((input, asset) -> {
        var mesh = mesh(input);
        int count = input.count(4);
        var names = new ArrayList<String>(count);
        for (int i = 0; i < count; i++) names.add(input.text());
        return new VehicleGeometry(mesh, names, textures(input, asset));
    }, (output, value) -> {
        mesh(output, value.mesh());
        output.writeInt(value.materialNames().size());
        for (var name : value.materialNames()) text(output, name);
        textures(output, value.textures());
    });

    @FunctionalInterface
    private interface Decoder<T> { T read(Reader input, PackageAsset asset) throws IOException; }
    @FunctionalInterface
    private interface Encoder<T> { void write(DataOutputStream output, T value) throws IOException; }

    private static <T> Codec<T> codec(Decoder<T> decoder, Encoder<T> encoder) {
        return new Codec<>() {
            @Override public T read(Reader input, PackageAsset asset) throws IOException { return decoder.read(input, asset); }
            @Override public void write(DataOutputStream output, T value) throws IOException { encoder.write(output, value); }
        };
    }

    private static void text(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static ModelBounds bounds(Reader input) throws IOException {
        var value = new ModelBounds(input.number(), input.number(), input.number(),
                input.number(), input.number(), input.number());
        require(value.minX() <= value.maxX() && value.minY() <= value.maxY() && value.minZ() <= value.maxZ(),
                "Invalid model bounds");
        return value;
    }

    private static void bounds(DataOutputStream output, ModelBounds value) throws IOException {
        output.writeFloat(value.minX()); output.writeFloat(value.minY()); output.writeFloat(value.minZ());
        output.writeFloat(value.maxX()); output.writeFloat(value.maxY()); output.writeFloat(value.maxZ());
    }

    private static ModelData.Material material(Reader input) throws IOException {
        String name = input.text();
        float red = input.number(), green = input.number(), blue = input.number(), alpha = input.number();
        String texture = input.readBoolean() ? input.text() : null;
        return new ModelData.Material(name, red, green, blue, alpha, texture, input.readBoolean());
    }

    private static void material(DataOutputStream output, ModelData.Material value) throws IOException {
        text(output, value.name());
        output.writeFloat(value.red()); output.writeFloat(value.green()); output.writeFloat(value.blue());
        output.writeFloat(value.alpha());
        output.writeBoolean(value.texture() != null);
        if (value.texture() != null) text(output, value.texture());
        output.writeBoolean(value.doubleSided());
    }

    private static ModelData model(Reader input) throws IOException {
        int materialCount = input.count(22);
        var materials = new ArrayList<ModelData.Material>(materialCount);
        for (int i = 0; i < materialCount; i++) materials.add(material(input));
        int partCount = input.count(18);
        var parts = new ArrayList<ModelData.Part>(partCount);
        for (int i = 0; i < partCount; i++) {
            String name = input.text();
            boolean visible = input.readBoolean(), smooth = input.readBoolean();
            float facet = input.number();
            int vertexCount = input.count(12);
            var positions = new ArrayList<ModelData.Position>(vertexCount);
            for (int v = 0; v < vertexCount; v++) {
                positions.add(new ModelData.Position(input.number(), input.number(), input.number()));
            }
            int faceCount = input.count(9);
            var faces = new ArrayList<ModelData.Face>(faceCount);
            for (int f = 0; f < faceCount; f++) {
                int corners = input.count(16);
                // MQO can retain two-corner edges; ModelMesh skips those when generating triangles.
                require(corners >= 2 && corners <= Integer.MAX_VALUE / 3, "Invalid face corner count");
                int material = input.readInt();
                require(material >= -1 && material < materialCount, "Invalid face material");
                int[] indices = new int[corners], colors = new int[corners];
                float[] uv = new float[corners * 2];
                for (int c = 0; c < corners; c++) indices[c] = index(input, vertexCount);
                for (int c = 0; c < uv.length; c++) uv[c] = input.number();
                for (int c = 0; c < corners; c++) colors[c] = input.readInt();
                float[] normals = null;
                if (input.readBoolean()) {
                    normals = new float[corners * 3];
                    for (int n = 0; n < normals.length; n++) normals[n] = input.number();
                }
                faces.add(new ModelData.Face(indices, uv, colors, material, normals));
            }
            parts.add(new ModelData.Part(name, visible, smooth, facet, positions, faces));
        }
        return new ModelData(materials, parts);
    }

    private static void model(DataOutputStream output, ModelData value) throws IOException {
        output.writeInt(value.materials().size());
        for (var material : value.materials()) material(output, material);
        output.writeInt(value.parts().size());
        for (var part : value.parts()) {
            text(output, part.name());
            output.writeBoolean(part.visible()); output.writeBoolean(part.smooth()); output.writeFloat(part.facet());
            output.writeInt(part.positions().size());
            for (var position : part.positions()) {
                output.writeFloat(position.x()); output.writeFloat(position.y()); output.writeFloat(position.z());
            }
            output.writeInt(part.faces().size());
            for (var face : part.faces()) {
                output.writeInt(face.indices().length);
                output.writeInt(face.material());
                for (int index : face.indices()) output.writeInt(index);
                for (float uv : face.uv()) output.writeFloat(uv);
                for (int color : face.colors()) output.writeInt(color);
                output.writeBoolean(face.normals() != null);
                if (face.normals() != null) for (float normal : face.normals()) output.writeFloat(normal);
            }
        }
    }

    private static int index(Reader input, int vertices) throws IOException {
        int index = input.readInt();
        require(index >= 0 && index < vertices, "Index outside vertex array");
        return index;
    }

    private static ModelMesh mesh(Reader input) throws IOException {
        int triangles = input.readInt(), fanFallbacks = input.readInt();
        require(triangles > 0 && fanFallbacks >= 0, "Invalid mesh counts");
        int partCount = input.count(9);
        var parts = new ArrayList<ModelMesh.Part>(partCount);
        long actualTriangles = 0;
        for (int p = 0; p < partCount; p++) {
            String name = input.text();
            boolean visible = input.readBoolean();
            int batchCount = input.count(30);
            var batches = new ArrayList<ModelMesh.Batch>(batchCount);
            for (int b = 0; b < batchCount; b++) {
                var material = material(input);
                int vertexCount = input.count(36);
                require(vertexCount <= Integer.MAX_VALUE / 8, "Invalid mesh vertex count");
                float[] vertices = new float[vertexCount * 8];
                int[] colors = new int[vertexCount];
                for (int v = 0; v < vertexCount; v++) {
                    for (int a = 0; a < 8; a++) vertices[v * 8 + a] = input.number();
                    colors[v] = input.readInt();
                }
                int indexCount = input.count(4);
                require(indexCount % 3 == 0, "Incomplete triangle indices");
                int[] indices = new int[indexCount];
                for (int i = 0; i < indexCount; i++) indices[i] = index(input, vertexCount);
                actualTriangles += indexCount / 3;
                batches.add(new ModelMesh.Batch(material, new IndexedMesh(vertices, colors, indices)));
            }
            parts.add(new ModelMesh.Part(name, visible, List.copyOf(batches)));
        }
        require(actualTriangles == triangles, "Mesh triangle count mismatch");
        return new ModelMesh(List.copyOf(parts), triangles, fanFallbacks);
    }

    private static void mesh(DataOutputStream output, ModelMesh value) throws IOException {
        output.writeInt(value.triangleCount()); output.writeInt(value.fanFallbackCount());
        output.writeInt(value.parts().size());
        for (var part : value.parts()) {
            text(output, part.name()); output.writeBoolean(part.visible());
            output.writeInt(part.batches().size());
            for (var batch : part.batches()) {
                material(output, batch.material());
                var mesh = batch.mesh();
                output.writeInt(mesh.colors().length);
                for (int v = 0; v < mesh.colors().length; v++) {
                    for (int a = 0; a < 8; a++) output.writeFloat(mesh.vertices()[v * 8 + a]);
                    output.writeInt(mesh.colors()[v]);
                }
                output.writeInt(mesh.indices().length);
                for (int index : mesh.indices()) output.writeInt(index);
            }
        }
    }

    private static Map<String, ModelTexture> textures(Reader input, PackageAsset asset) throws IOException {
        int count = input.count(9);
        var textures = new HashMap<String, ModelTexture>();
        for (int i = 0; i < count; i++) {
            String name = input.text(), entry = input.text();
            var texture = new ModelTexture(new PackageAsset(asset.archive(), entry), input.readBoolean());
            require(textures.put(name, texture) == null, "Duplicate texture binding");
        }
        return Map.copyOf(textures);
    }

    private static void textures(DataOutputStream output, Map<String, ModelTexture> value) throws IOException {
        output.writeInt(value.size());
        for (var entry : new TreeMap<>(value).entrySet()) {
            text(output, entry.getKey()); text(output, entry.getValue().asset().entry());
            output.writeBoolean(entry.getValue().alphaBlend());
        }
    }
}
