/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.track.mesh;

/** Layout-time context only; never serialized into track state or network payloads. */
public record NativeLayoutDiagnostic(String stage, String control, String path, int side,
                                     double from, double to, double required) {
    public static NativeLayoutDiagnostic at(String stage, String path, int side, double from, double to, double required) {
        return new NativeLayoutDiagnostic(stage, "standalone", path, side, from, to, required);
    }
    public NativeLayoutDiagnostic in(String control, String path) {
        return new NativeLayoutDiagnostic(stage, control, path, side, from, to, required);
    }
    @Override public String toString() {
        return "stage="+stage+" control="+control+" path="+path+" side="+side
                +" from="+from+" to="+to+" actual="+(to-from)+" required="+required;
    }
}
