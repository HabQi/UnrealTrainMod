/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm.script;

import com.unrealtrainmod.track.assembly.SwitchPosition;
import com.unrealtrainmod.track.assembly.TrackAssemblyInstance;

/** One native A-to-B/A-to-C turnout, with stable map identities for legacy script caches. */
public final class RailScriptSwitch {
    public static final class Point {
        public final RailScriptMap rmMain, rmBranch;
        public final boolean mainDirIsPositive, branchDirIsPositive;
        public final RailDir branchDir;
        private SwitchPosition position;

        private Point(RailScriptMap main, RailScriptMap branch, RailDir direction, SwitchPosition position) {
            rmMain = main;
            rmBranch = branch;
            branchDir = direction;
            // Maps run A -> B/C. Only the common entrance is their positive endpoint.
            mainDirIsPositive = direction != RailDir.NONE;
            branchDirIsPositive = direction != RailDir.NONE;
            this.position = position;
        }

        public float getMovement() { return branchDir == RailDir.NONE || position == SwitchPosition.NORMAL ? 0 : 1; }
    }

    private final Point[] points;
    private final RailScriptMap[] maps;

    RailScriptSwitch(TrackAssemblyInstance assembly) {
        var paths = assembly.geometry().paths();
        var main = new RailScriptMap(paths.get("straight").getFirst());
        var branch = new RailScriptMap(paths.get("diverging").getFirst());
        maps = new RailScriptMap[]{main, branch};
        var mainEnd = main.geometry().sample(main.getLength());
        var branchEnd = branch.geometry().sample(branch.getLength()).position();
        double dx = branchEnd.x() - mainEnd.position().x(), dz = branchEnd.z() - mainEnd.position().z();
        double side = dx * mainEnd.lateral().x() + dz * mainEnd.lateral().z();
        var direction = side > 0 ? RailDir.LEFT : RailDir.RIGHT;
        points = new Point[]{
                new Point(main, branch, direction, assembly.state().controls().get("main")),
                new Point(main, null, RailDir.NONE, SwitchPosition.NORMAL),
                new Point(branch, null, RailDir.NONE, SwitchPosition.NORMAL)};
    }

    public Point[] getPoints() { return points.clone(); }

    RailScriptMap[] maps() { return maps.clone(); }

    void position(SwitchPosition position) { points[0].position = position; }
}
