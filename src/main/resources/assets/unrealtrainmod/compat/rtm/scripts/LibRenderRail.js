/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
// Static-stage compatibility for legacy rail scripts. Dynamic drawing is not replayed here.
function sigmoid2(x) {
    var scaled = 3.5 * x;
    return 0.25 + 0.75 * scaled / Math.sqrt(1 + scaled * scaled);
}

function renderRailDynamic2(tileEntity, x, y, z) {
    throw new Error("当前阶段不支持动态轨道绘制: renderRailDynamic2");
}

function renderPoint(tileEntity, point) {
    throw new Error("当前阶段不支持动态轨道绘制: renderPoint");
}

function renderRailMapDynamic(tileEntity, railMap, direction, forward, movement, tongueIndex) {
    throw new Error("当前阶段不支持动态轨道绘制: renderRailMapDynamic");
}
