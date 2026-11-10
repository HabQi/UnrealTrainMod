/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
// Independently implemented WTRE rail helpers. Draws record reusable rigid parts, never GL geometry.
function sigmoid2(x) {
    var scaled = 3.5 * x;
    return 0.25 + 0.75 * scaled / Math.sqrt(1 + scaled * scaled);
}

function renderRailDynamic2(tileEntity, x, y, z) {
    var junction = tileEntity.getSwitch();
    if (junction === null) return;
    var anchor = tileEntity.getRailPositions()[0];
    GL11.glPushMatrix();
    try {
        GL11.glTranslatef(x + anchor.posX - anchor.blockX, y, z + anchor.posZ - anchor.blockZ);
        var ends = junction.getPoints();
        for (var endpoint = 0; endpoint < ends.length; endpoint++) renderPoint(tileEntity, ends[endpoint]);
    } finally {
        GL11.glPopMatrix();
    }
}

function renderPoint(tileEntity, point) {
    var track = point.rmMain;
    var divisions = Math.max(1, Math.floor(track.getLength() * 2));
    var boundary = Math.floor(divisions / 2);
    if (point.branchDir == RailDir.NONE) {
        renderer.renderRailMapStatic(tileEntity, track, divisions,
                point.mainDirIsPositive ? 0 : boundary + 1,
                point.mainDirIsPositive ? boundary : divisions, leftParts, rightParts);
        return;
    }
    var tip = Math.floor(divisions * TONG_POS);
    var position = point.getMovement();
    renderRailMapDynamic(tileEntity, track, point.branchDir, point.mainDirIsPositive,
            position * TONG_MOVE, tip);
    renderRailMapDynamic(tileEntity, point.rmBranch, point.branchDir.invert(), point.branchDirIsPositive,
            (1 - position) * TONG_MOVE, tip);
}

function renderRailMapDynamic(tileEntity, railMap, direction, forward, movement, tongueIndex) {
    var count = Math.max(1, Math.floor(railMap.getLength() * 2));
    var middle = Math.floor(count / 2);
    var first = forward ? 0 : middle + 1;
    var last = forward ? middle : count;
    var tip = Math.max(0, Math.min(middle, Math.floor(tongueIndex)));
    var innerLeft = (direction == RailDir.LEFT) === !!forward;
    var fixed = innerLeft ? rightParts : leftParts;
    var moving = innerLeft ? leftParts : rightParts;
    var blade = forward ? (innerLeft ? tongBL : tongBR) : (innerLeft ? tongFL : tongFR);
    var sign = innerLeft ? -1 : 1;
    var scale = renderer.getRailModelScale();
    var pivot = sign * HALF_GAUGE * scale;
    var anchor = tileEntity.getRailPositions()[0];
    for (var sample = first; sample <= last; sample++) {
        if (!railMap.ownsSample(count, sample)) continue;
        var at = railMap.getRailPos(count, sample);
        var fromToe = forward ? sample : count - sample;
        GL11.glPushMatrix();
        try {
            GL11.glTranslatef(at[1] - anchor.posX,
                    railMap.getRailHeight(count, sample) - anchor.posY, at[0] - anchor.posZ);
            GL11.glRotatef(railMap.getRailRotation(count, sample), 0, 1, 0);
            GL11.glRotatef(-railMap.getRailPitch(count, sample), 1, 0, 0);
            fixed.render(renderer);
            if (fromToe < tip) continue;
            // Normalize the easing so the moving rail meets the fixed half without a residual offset.
            var progress = Math.min(1, fromToe / Math.max(1, middle));
            var taper = (sigmoid2(1) - sigmoid2(progress)) / (sigmoid2(1) - sigmoid2(0));
            var offset = sign * movement * scale * taper;
            GL11.glTranslatef(offset - pivot, 0, 0);
            GL11.glRotatef(-offset * YAW_RATE / railMap.getLength() * (forward ? 1 : -1), 0, 1, 0);
            GL11.glTranslatef(pivot, 0, 0);
            (fromToe === tip ? blade : moving).render(renderer);
        } finally {
            GL11.glPopMatrix();
        }
    }
}
