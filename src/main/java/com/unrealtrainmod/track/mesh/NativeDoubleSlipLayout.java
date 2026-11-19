/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.track.mesh;

import com.unrealtrainmod.track.assembly.TrackAssemblyDefinition;
import com.unrealtrainmod.track.geometry.TrackGeometry;
import com.unrealtrainmod.track.geometry.TrackVector;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.DoubleFunction;

import static com.unrealtrainmod.track.mesh.NativeRailCurve.add;
import static com.unrealtrainmod.track.mesh.NativeRailCurve.dot;
import static com.unrealtrainmod.track.mesh.NativeRailCurve.multiply;

/** Railhead layout derived from the saved through and slip paths, independently of client resources. */
public record NativeDoubleSlipLayout(TrackVector origin, double scale, List<Strip> fixed, List<Control> controls,
                                     NativeDoubleSlipFittings fittings) {
    public static final double HEAD_WIDTH = 2 * (NativeDiamondLayout.RAIL_X - NativeTurnoutLayout.RAIL_WORK_X);
    private static final double OPENING = .12;
    private static final double MAX_JOINT_ROTATION = Math.toRadians(.1);
    private static final List<String> CONTROLS = List.of("switch_one", "switch_two", "switch_three", "switch_four");

    public record Section(TrackVector point, TrackVector tangent, double width) {}
    public record Strip(Section start, Section end, int side) {}
    public record Link(Strip strip, double rotation) {}
    public record Blade(List<Link> links, boolean opensInNormal) {}
    public record Control(String name, List<Blade> blades) {}
    private record Gap(double start, double end) {}
    private record Cut(int rail, Gap gap) {}
    private record Port(TrackGeometry main, TrackGeometry other, TrackGeometry bend, boolean first, boolean reverse,
                        int side, double acute, double innerCrossing, double outerCrossing, double otherCrossing) {}

    public static TrackAssemblyDefinition.Geometry example() {
        double z = 16 * Math.tan(Math.toRadians(10));
        var points = List.of(new TrackVector(-16, 0, -z), new TrackVector(16, 0, z),
                new TrackVector(-16, 0, z), new TrackVector(16, 0, -z));
        return com.unrealtrainmod.track.AssemblyPlacementGeometry.create(
                com.unrealtrainmod.track.construction.TrackConstructionType.DOUBLE_SLIP,
                points, 1.435, java.util.Collections.nCopies(4, null), 0);
    }

    public static NativeDoubleSlipLayout build(TrackAssemblyDefinition.Geometry geometry, double gauge) {
        double scale = gauge / 1.435;
        var first = geometry.paths().get("first_straight").getFirst();
        var second = geometry.paths().get("second_straight").getFirst();
        var bends = List.of(geometry.paths().get("first_crossover").get(1), geometry.paths().get("second_crossover").get(1));
        var ends = NativeDiamondLayout.slipEnds(first, second, gauge);
        var fixed = new ArrayList<Strip>();
        var controls = new ArrayList<Control>();
        var cuts = new ArrayList<Cut>();
        double toe = scale, heel = toe + 2.222079 * scale;
        for (int i = 0; i < 4; i++) {
            boolean firstLine = i < 2, reverse = i % 2 != 0;
            var main = firstLine ? first : second;
            var other = firstLine ? second : first;
            int side = firstLine ? ends.firstInnerSide() : -ends.firstInnerSide();
            int forwardSide = reverse ? -side : side;
            var acute = reverse ? ends.exit() : ends.entrance();
            var innerCrossing = forwardSide == ends.firstInnerSide() ? ends.firstSide() : ends.oppositeSide();
            var outerCrossing = forwardSide == ends.firstInnerSide() ? ends.oppositeSide() : ends.firstSide();
            var bend = bends.get(firstLine == reverse ? 1 : 0);
            var port = new Port(reverse ? main.reversed() : main, other, reverse ? bend.reversed() : bend,
                    firstLine, reverse, side, station(main, acute, firstLine, reverse),
                    station(main, innerCrossing, firstLine, reverse), station(main, outerCrossing, firstLine, reverse),
                    station(other, innerCrossing, !firstLine, false));
            if (bend.getLength() <= 2 * heel) throw space("inner_blade_heels", CONTROLS.get(i), side, toe, bend.getLength(), 2 * heel);
            controls.add(new Control(CONTROLS.get(i), blades(port, forwardSide, cuts, scale, toe, heel)));
        }
        for (int i = 0; i < 4; i++) {
            int rail = i;
            var main = i < 2 ? first : second;
            int side = i % 2 == 0 ? -1 : 1;
            double from = 0;
            for (var cut : cuts.stream().filter(c -> c.rail() == rail).map(Cut::gap)
                    .sorted(Comparator.comparingDouble(Gap::start)).toList()) {
                if (cut.start() < from || cut.end() > main.getLength())
                    throw space("main_rail_cuts", "through", side, from, cut.start(), cut.end());
                fixed(fixed, main, from, cut.start(), side, scale);
                from = cut.end();
            }
            fixed(fixed, main, from, main.getLength(), side, scale);
        }
        for (int i = 0; i < bends.size(); i++) {
            var bend = bends.get(i);
            int side = i == 0 ? ends.firstInnerSide() : -ends.firstInnerSide();
            fixed(fixed, bend, 0, bend.getLength(), -side, scale);
            fixed(fixed, bend, heel, bend.getLength() - heel, side, scale);
        }
        return new NativeDoubleSlipLayout(first.sample(0).position(), scale, List.copyOf(fixed), List.copyOf(controls),
                NativeDoubleSlipFittings.build(first,second,ends,scale,controls));
    }

    private static List<Blade> blades(Port port, int forwardSide, List<Cut> cuts, double scale, double toe, double heel) {
        var main = port.main();
        var bend = port.bend();
        int side = port.side();
        double offset = NativeTurnoutLayout.RAIL_WORK_X * scale;
        double outerHeel = port.outerCrossing() - .90 * scale;
        double pivot = port.innerCrossing() - .75 * scale;
        double nose = port.innerCrossing() - .04 * scale;
        double crossingEnd = port.innerCrossing() + .04 * scale;
        if (outerHeel <= port.acute() || pivot <= port.acute() || crossingEnd >= main.getLength())
            throw space("blade_room", "through", side, port.acute(), Math.min(outerHeel, pivot), crossingEnd);

        double stockToe = aligned(main, port.acute() + toe, side * offset,
                edge(bend, toe, side * offset), bend.sample(toe).tangent());
        var start = edge(main, stockToe, side * offset);
        var reference = NativeFixedRails.reference(bend, toe, heel, side * offset, start,
                edge(bend, heel, side * offset), yaw(main.sample(stockToe).tangent()), yaw(bend.sample(heel).tangent()), scale);
        DoubleFunction<Section> slip = s -> {
            var knot = reference.at(s);
            var direction = unit(knot.first());
            return trimmed(knot.point(), direction, side, main, stockToe + s, side * offset, scale);
        };
        DoubleFunction<Section> through = s -> {
            var frame = main.sample(port.acute() + s);
            return trimmed(add(frame.position(), frame.lateral(), -side * offset), frame.tangent(),
                    -side, bend, s, -side * offset, scale);
        };
        double blueLength = outerHeel - port.acute();
        if (slip.apply(heel-toe).width() < HEAD_WIDTH - .0001 || through.apply(blueLength).width() < HEAD_WIDTH - .0001)
            throw space("blade_heel_width", "slip", side, 0, blueLength, HEAD_WIDTH);
        addGap(cuts, port, -side, port.acute(), outerHeel);
        addGap(cuts, port, side, pivot, crossingEnd);

        var atNose = main.sample(nose); var atPivot = main.sample(pivot);
        var tip = trimmed(add(atNose.position(), atNose.lateral(), side * offset),
                multiply(atNose.tangent(), -1), -side, port.other(), port.otherCrossing(), forwardSide * offset, scale);
        var root = new Section(add(atPivot.position(), atPivot.lateral(), side * offset),
                multiply(atPivot.tangent(), -1), HEAD_WIDTH);
        var red = new Strip(tip, root, -side);
        double length = length(add(root.point(), tip.point(), -1));
        if (length <= OPENING * scale) throw space("short_rail_travel", "slip", side, 0, length, OPENING * scale);
        double redRotation = -side * Math.asin(OPENING * scale / length);
        return List.of(flexible(slip, heel-toe, heel-toe-.42*scale, side, true, scale),
                flexible(through, blueLength, 0, -side, false, scale),
                new Blade(List.of(new Link(red, redRotation)), false));
    }

    private static void addGap(List<Cut> cuts, Port port, int side, double start, double end) {
        double length = port.main().getLength();
        int forwardSide = port.reverse() ? -side : side;
        int rail = (port.first() ? 0 : 2) + (forwardSide < 0 ? 0 : 1);
        cuts.add(new Cut(rail, port.reverse() ? new Gap(length - end, length - start) : new Gap(start, end)));
    }

    private static double station(TrackGeometry path, NativeDiamondLayout.SlipEnd junction, boolean first, boolean reverse) {
        double station = first ? junction.firstStation() : junction.secondStation();
        return reverse ? path.getLength() - station : station;
    }

    private static Blade flexible(DoubleFunction<Section> section, double length, double rigid, int side,
                                  boolean opensInNormal, double scale) {
        int flexCount = Math.max(3, (int)Math.ceil((length-rigid)/(.15*scale)));
        while (true) {
            var strips = new ArrayList<Strip>();
            var weights = new ArrayList<Double>();
            int front = (int)Math.ceil(rigid/(.25*scale));
            var previous = section.apply(0);
            for (int i = 1; i <= front+flexCount; i++) {
                double t = (i-front)/(double)flexCount;
                double distance = i <= front ? rigid*i/front : rigid+(length-rigid)*t;
                var next = section.apply(distance);
                strips.add(new Strip(previous, next, side));
                double u = i <= front ? 0 : t - .5/flexCount;
                weights.add(1-u*u*u*(10+u*(-15+6*u)));
                previous = next;
            }
            double low = 0, high = .25;
            double target = OPENING*scale;
            if (travel(strips, weights, high*side, side) < target)
                throw space("blade_travel", "slip", side, 0, length, target);
            for (int i = 0; i < 30; i++) {
                double test = (low+high)/2;
                if (travel(strips,weights,test*side,side) < target) low = test;
                else high = test;
            }
            int required = (int)Math.ceil(1.875*high/MAX_JOINT_ROTATION);
            if (required > flexCount) { flexCount = required; continue; }
            var links = new ArrayList<Link>(strips.size());
            for (int i = 0; i < strips.size(); i++) links.add(new Link(strips.get(i), side*high*weights.get(i)));
            return new Blade(List.copyOf(links), opensInNormal);
        }
    }

    private static double travel(List<Strip> strips, List<Double> weights, double rotation, int side) {
        var lateral = lateral(strips.getFirst().start().tangent());
        double opening = 0;
        for (int i = 0; i < strips.size(); i++) {
            var strip = strips.get(i);
            var chord = add(strip.end().point(), strip.start().point(), -1);
            double angle = yaw(chord) + rotation*weights.get(i);
            opening += dot(add(chord, new TrackVector(Math.sin(angle), 0, Math.cos(angle)), -length(chord)), lateral);
        }
        return -side*opening;
    }

    private static void fixed(List<Strip> strips, TrackGeometry path, double from, double to, int side, double scale) {
        if (to-from < 1e-6*scale) return;
        double offset = side*NativeTurnoutLayout.RAIL_WORK_X*scale;
        var previous = at(path,from,offset); var last = at(path,to,offset);
        var curve = NativeFixedRails.reference(path,from,to,offset,previous.point(),last.point(),
                yaw(previous.tangent()),yaw(last.tangent()),scale);
        var cuts = NativeFixedRails.cuts(curve,scale);
        for (int i = 1; i < cuts.size(); i++) {
            var next = i+1 == cuts.size() ? last : at(path,from+cuts.get(i),offset);
            strips.add(new Strip(previous,next,side)); previous = next;
        }
    }

    private static Section at(TrackGeometry path, double station, double offset) {
        var pose = path.sample(station);
        return new Section(add(pose.position(),pose.lateral(),offset),pose.tangent(),HEAD_WIDTH);
    }

    private static Section trimmed(TrackVector point, TrackVector direction, int side, TrackGeometry face,
                                   double guess, double offset, double scale) {
        double station = aligned(face, guess, offset, point, direction);
        double available = side * dot(add(edge(face,station,offset),point,-1), lateral(direction)) / scale;
        if (available < -.00025) throw space("railhead_clearance", "slip", side, guess, available, HEAD_WIDTH);
        double blend = Math.max(0, 1-Math.abs(available-HEAD_WIDTH)/.008);
        double width = Math.min(available, HEAD_WIDTH) - .008*blend*blend*blend/6;
        return new Section(point, direction, Math.max(0, width));
    }

    private static double aligned(TrackGeometry path, double guess, double offset, TrackVector point, TrackVector tangent) {
        double s = Math.clamp(guess, 0, path.getLength());
        for (int i = 0; i < 12; i++) {
            var pose = path.sample(s);
            double f = dot(add(add(pose.position(),pose.lateral(),offset),point,-1),tangent);
            if (Math.abs(f) < 1e-8) return s;
            double rate = dot(pose.tangent(),tangent)*(1-offset*pose.curvature());
            if (Math.abs(rate) < 1e-6) throw new NativeDoubleSlipSolver.InvalidLayout(NativeDoubleSlipSolver.Problem.SPACE);
            double next = Math.clamp(s-f/rate,0,path.getLength());
            if (s == next) break;
            s = next;
        }
        if (Math.abs(dot(add(edge(path,s,offset),point,-1),tangent)) < 2e-5) return s;
        throw space("railhead_projection", "slip", 0, s, guess, path.getLength());
    }

    private static TrackVector edge(TrackGeometry path, double s, double offset) {
        return NativeFixedRails.edge(path,s,offset);
    }
    public static TrackVector lateral(TrackVector t) { return new TrackVector(t.z(),0,-t.x()); }
    public static double yaw(TrackVector t) { return Math.atan2(t.x(),t.z()); }
    public static double length(TrackVector t) { return Math.hypot(t.x(),t.z()); }
    private static TrackVector unit(TrackVector t) { return multiply(t,1/length(t)); }
    private static NativeDoubleSlipSolver.InvalidLayout space(String stage, String path, int side,
                                                              double station, double value, double limit) {
        return new NativeDoubleSlipSolver.InvalidLayout(NativeDoubleSlipSolver.Problem.SPACE,
                NativeLayoutDiagnostic.at(stage, path, side, station, value, limit));
    }
}
