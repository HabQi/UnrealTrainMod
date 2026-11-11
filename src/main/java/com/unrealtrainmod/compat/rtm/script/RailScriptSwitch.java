/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm.script;

/** One read-only A-to-B/A-to-C rendering view, with stable map identities for legacy script caches. */
public final class RailScriptSwitch {
    public static final class Point {
        public final RailScriptMap rmMain, rmBranch;
        public final boolean mainDirIsPositive, branchDirIsPositive;
        public final RailDir branchDir;
        private float movement;

        private Point(RailScriptMap main, RailScriptMap branch, RailDir direction) {
            rmMain = main;
            rmBranch = branch;
            branchDir = direction;
            // Maps run A -> B/C. Only the common entrance is their positive endpoint.
            mainDirIsPositive = direction != RailDir.NONE;
            branchDirIsPositive = direction != RailDir.NONE;
        }

        public float getMovement() { return branchDir == RailDir.NONE ? 0 : movement; }
    }

    private final Point[] points;
    private final RailScriptMap[] maps;

    RailScriptSwitch(RailTurnoutView view) {
        var main = new RailScriptMap(view.main(), true);
        var branch = new RailScriptMap(view.branch(), true);
        maps = new RailScriptMap[]{main, branch};
        var mainEnd = main.geometry().sample(main.getLength());
        var branchEnd = branch.geometry().sample(branch.getLength()).position();
        double dx = branchEnd.x() - mainEnd.position().x(), dz = branchEnd.z() - mainEnd.position().z();
        double side = dx * mainEnd.lateral().x() + dz * mainEnd.lateral().z();
        var direction = side > 0 ? RailDir.LEFT : RailDir.RIGHT;
        points = new Point[]{
                new Point(main, branch, direction),
                new Point(main, null, RailDir.NONE),
                new Point(branch, null, RailDir.NONE)};
    }

    public Point[] getPoints() { return points.clone(); }

    RailScriptMap[] maps() { return maps.clone(); }

    void movement(float value) { points[0].movement = value; }
}
