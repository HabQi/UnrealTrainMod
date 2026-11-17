/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.client.track;

import com.unrealtrainmod.model.IndexedMesh;
import com.unrealtrainmod.track.mesh.RailTrackMeshBuilder.Source;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Resource-time, orientation-sensitive geometry identities, independent of authored part names. */
final class NativeSourceIdentity {
    // Model resources keep their identity through world resets; replaced resource generations are not retained.
    private static final Map<IndexedMesh,String> IDS = new WeakHashMap<>();
    private NativeSourceIdentity() {}
    static synchronized void register(IndexedMesh mesh) { IDS.computeIfAbsent(mesh,NativeSourceIdentity::calculate); }
    static synchronized String group(List<Source> sources) {
        var ids = new ArrayList<String>();
        for (var source : sources) ids.add(source.texture()+":"+source.doubleSided()+":"+IDS.computeIfAbsent(source.mesh(),NativeSourceIdentity::calculate));
        ids.sort(String::compareTo); return String.join("|",ids);
    }
    private static String calculate(IndexedMesh mesh) {
        var triangles = new ArrayList<int[]>(mesh.indices().length/3);
        for (int offset = 0; offset < mesh.indices().length; offset += 3) {
            int[][] vertices = new int[3][9];
            for (int corner = 0; corner < 3; corner++) {
                int vertex = mesh.indices()[offset+corner], first = vertex*8;
                for (int i = 0; i < 8; i++) {
                    float value = mesh.vertices()[first+i];
                    vertices[corner][i] = i < 3 ? Math.round(value*1_000_000) : value == 0 ? 0 : Float.floatToIntBits(value);
                }
                vertices[corner][8] = mesh.colors()[vertex];
            }
            int first = 0;
            for (int i = 1; i < 3; i++) if (Arrays.compare(vertices[i],vertices[first]) < 0) first = i;
            var triangle = new int[27];
            for (int i = 0; i < 3; i++) System.arraycopy(vertices[(first+i)%3],0,triangle,i*9,9);
            triangles.add(triangle);
        }
        triangles.sort(Arrays::compare);
        try {
            var hash = MessageDigest.getInstance("SHA-256"); var bytes = ByteBuffer.allocate(27*Integer.BYTES);
            for (var triangle : triangles) { bytes.clear(); for (int value : triangle) bytes.putInt(value); hash.update(bytes.array()); }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
