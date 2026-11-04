/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.compat.rtm.script;

import com.unrealtrainmod.track.geometry.TrackGeometry;
import com.unrealtrainmod.track.assembly.SwitchPosition;
import com.unrealtrainmod.track.assembly.TrackAssemblyInstance;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;

/** Script-local tile view. Assembly maps and CPU lists survive individual chunk builds. */
public final class RailScriptTile {
    public static final class Property {
        public final String railModel;

        private Property(String railModel) { this.railModel = railModel; }
    }

    private final Property property;
    private final int[] startPoint = new int[3];
    private RailScriptMap map;
    private RailScriptSwitch railSwitch;
    private final Map<String, ScriptGLList> lists = new HashMap<>();
    private RailScriptMap.Position anchor;

    public RailScriptTile(String railModel) {
        property = new Property(railModel == null ? "" : railModel);
    }

    void assembly(TrackAssemblyInstance assembly) {
        railSwitch = new RailScriptSwitch(assembly);
        var origin = assembly.geometry().ports().get("A").position();
        var position = BlockPos.containing(origin.x(), origin.y() + RailScriptMap.HEIGHT_OFFSET, origin.z());
        setStart(position);
        anchor = new RailScriptMap.Position(position, origin.y());
    }

    void position(SwitchPosition position) { railSwitch.position(position); }

    void map(RailScriptMap map) { this.map = map; }

    public Property getProperty() { return property; }

    /** Whole-segment start block, shared by all chunk meshes; previews use a single-module straight line. */
    public int[] getStartPoint() { return startPoint; }

    void bind(TrackGeometry geometry, BlockPos position) {
        map = new RailScriptMap(geometry);
        setStart(position);
    }

    private void setStart(BlockPos position) {
        startPoint[0] = position.getX();
        startPoint[1] = position.getY();
        startPoint[2] = position.getZ();
    }

    void unbind() { map = null; }

    public RailScriptMap getRailMap(Object ignored) { return map; }

    public RailScriptMap[] getAllRailMaps() { return railSwitch == null ? new RailScriptMap[]{map} : railSwitch.maps(); }

    public RailScriptMap.Position[] getRailPositions() {
        if (railSwitch != null) {
            var maps = railSwitch.maps();
            return new RailScriptMap.Position[]{anchor, maps[0].getEndRP(), maps[1].getEndRP()};
        }
        return new RailScriptMap.Position[]{map.getStartRP(), map.getEndRP()};
    }

    public RailScriptSwitch getSwitch() { return railSwitch; }

    public ScriptGLList getScriptGLList(String key) { return lists.get(key); }

    public void setScriptGLList(String key, ScriptGLList list) {
        var previous = list == null ? lists.remove(key) : lists.put(key, list);
        if (previous != null && previous != list) previous.delete();
    }

    void close() {
        for (var list : lists.values()) list.delete();
        lists.clear();
        railSwitch = null;
        anchor = null;
        unbind();
    }
}
