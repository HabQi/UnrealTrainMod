/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.wiring;

import com.unrealtrainmod.resource.PackageAsset;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import net.minecraft.world.phys.Vec3;

/** Exact, reviewed package programs only; a changed program keeps its dynamic semantics. */
public record WiringScriptPolicy(boolean endpointOnly, int minimumSpan) {
    private static final WiringScriptPolicy DYNAMIC = new WiringScriptPolicy(false, 0);
    private static final WiringScriptPolicy NATIVE = new WiringScriptPolicy(true, 0);
    private static final Map<PackageAsset, WiringScriptPolicy> CACHE = new HashMap<>();

    public static synchronized WiringScriptPolicy of(PackageAsset asset) {
        if (asset == null) return NATIVE;
        return CACHE.computeIfAbsent(asset, source -> {
            try (var input = source.open()) {
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
                return switch (hash) {
                    case "611fdcd2340fff8374edcf3873db795dbaa130cbb9b5396219c478cdb1f530b7" -> new WiringScriptPolicy(true, 2);
                    case "c11ba852ad59543714c969be8382b3bf69698733be2fe7ec336ea840f4fe8ad9" -> new WiringScriptPolicy(true, 4);
                    case "23019d04c76f565217199e6b796acb115cc320f18c5e1e89a338810ad22302e3" -> new WiringScriptPolicy(true, 0);
                    default -> DYNAMIC;
                };
            } catch (IOException e) { return DYNAMIC; }
            catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        });
    }

    public boolean validSpan(Vec3 delta) {
        // These reviewed legacy beam programs use this effective length, including their authored Y term.
        return minimumSpan == 0 || delta.x * delta.x + delta.x * delta.y + delta.z * delta.z >= minimumSpan * minimumSpan;
    }

    public static synchronized void clear() { CACHE.clear(); }
}
