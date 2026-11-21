/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.track.mesh;

import com.unrealtrainmod.track.assembly.TrackAssemblyDefinition;
import com.unrealtrainmod.track.geometry.TrackGeometry;
import com.unrealtrainmod.track.geometry.TrackPose;
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
    static final double FOOT_INNER = .075-HEAD_WIDTH/2;
    static final double FOOT_OUTER = .1105;
    private static final double FLEX_BLADE_OPENING = .14;
    private static final double SHORT_RAIL_OPENING = .12;
    private static final double INNER_TOE_TRIM = .32;
    private static final double OUTER_TOE_TRIM = .90;
    private static final double INNER_FOOT_RELIEF = .060;
    private static final double INNER_HEEL = 3.222079;
    private static final double INNER_ARC_EXTENSION = 1;
    private static final double INNER_FLEX_EXTENSION = 1.5;
    private static final double FIXED_INNER_RAIL_RESERVE = .40;
    private static final double FLEX_SEGMENT_LENGTH = .15;
    private static final double MAX_JOINT_ROTATION = Math.toRadians(.1);
    private static final List<String> CONTROLS = List.of("switch_one", "switch_two", "switch_three", "switch_four");

    public record Section(TrackVector point, TrackVector tangent, double width, TrackVector across,
                          double footLimit, double bevel, double footRelief) {
        public Section(TrackVector point, TrackVector tangent, double width, TrackVector across,
                       double footLimit, double bevel) {
            this(point,tangent,width,across,footLimit,bevel,0);
        }
        public Section(TrackVector point, TrackVector tangent, double width) {
            this(point,tangent,width,lateral(tangent),FOOT_OUTER,0);
        }
        Section reversed() {
            return new Section(point,multiply(tangent,-1),width,multiply(across,-1),footLimit,bevel,footRelief);
        }
        Section interpolate(Section other, double t) {
            return new Section(add(point,add(other.point,point,-1),t),
                    add(tangent,add(other.tangent,tangent,-1),t), Math.fma(other.width-width,t,width),
                    add(across,add(other.across,across,-1),t), Math.fma(other.footLimit-footLimit,t,footLimit),
                    Math.fma(other.bevel-bevel,t,bevel), Math.fma(other.footRelief-footRelief,t,footRelief));
        }
    }
    public record Strip(Section start, Section end, int side) {}
    /** visibleFrom is the fraction of the link from its toe toward its heel. */
    public record Link(Strip strip, double rotation, float visibleFrom) {}
    public record Blade(List<Link> links, boolean opensInNormal) {}
    public record Control(String name, List<Blade> blades) {}
    private record Gap(double start, double end, Section before, Section after) {}
    private record Cut(int rail, Gap gap) {}
    private record Blades(List<Control> controls, List<List<Gap>> gaps) {}
    private record Port(TrackGeometry main, TrackGeometry other, TrackGeometry bend, boolean first, boolean reverse,
                        int side, double transition, double innerCrossing, double outerCrossing, double otherCrossing,
                        TrackVector crossingPoint) {}

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
        var firstSlip = geometry.paths().get("first_crossover");
        var secondSlip = geometry.paths().get("second_crossover");
        var bends = List.of(firstSlip.get(1), secondSlip.get(1));
        var ends = NativeDiamondLayout.slipEnds(first, second, gauge);
        var firstPath = new NativeDiamondLayout.Path(first);
        var secondPath = new NativeDiamondLayout.Path(second);
        var frogs = NativeDiamondLayout.slipFrogs(firstPath,secondPath,ends,scale);
        var fixed = new ArrayList<Strip>();
        var blades = prepareBlades(first,second,bends,ends,scale);
        for (int i = 0; i < 4; i++) {
            var main = i < 2 ? first : second;
            int side = i % 2 == 0 ? -1 : 1;
            var gaps = new ArrayList<>(blades.gaps().get(i));
            for (var frog : frogs.rails()) {
                if (frog.second() == (i >= 2) && frog.tail().side() == side)
                    gaps.add(frogGap(frog,scale));
            }
            gaps.sort(Comparator.comparingDouble(Gap::start));
            double from = 0;
            Section start = null;
            // A frog wing can reach a moving cut. Fill only the fixed intervals outside both.
            for (var gap : gaps) {
                fixed(fixed,main,from,gap.start(),side,scale,start,gap.before());
                if (gap.end() > from) {
                    from = gap.end();
                    start = gap.after();
                }
            }
            fixed(fixed,main,from,main.getLength(),side,scale,start,null);
        }
        for (int i = 0; i < bends.size(); i++) {
            var bend = bends.get(i);
            double heel = heel(bend, scale, INNER_ARC_EXTENSION + INNER_FLEX_EXTENSION);
            int side = i == 0 ? ends.firstInnerSide() : -ends.firstInnerSide();
            fixed(fixed, bend, 0, bend.getLength(), -side, scale);
            fixed(fixed, bend, heel, bend.getLength() - heel, side, scale);
        }
        var controls = blades.controls().stream().map(control -> {
            var rails = control.blades();
            return new Control(control.name(),List.of(spreadSlipOpening(rails.getFirst()),rails.get(1),rails.getLast()));
        }).toList();
        return new NativeDoubleSlipLayout(first.sample(0).position(), scale, List.copyOf(fixed), controls,
                NativeDoubleSlipFittings.build(firstPath, secondPath, firstSlip, secondSlip, ends, frogs, scale));
    }

    static void validateBlades(TrackGeometry first, TrackGeometry second, NativeDoubleSlipSolver.Solution solution,
                               NativeDiamondLayout.SlipEnds ends, double scale) {
        prepareBlades(first,second,List.of(solution.first().get(1),solution.second().get(1)),ends,scale);
    }

    private static double heel(TrackGeometry bend, double scale, double extension) {
        double base = INNER_HEEL*scale;
        // Both ends extend equally into the same fixed rail.
        double room = (bend.getLength() - 2*base - FIXED_INNER_RAIL_RESERVE*scale)/2;
        return base + Math.clamp(room, 0, extension*scale);
    }

    private static Blades prepareBlades(TrackGeometry first, TrackGeometry second, List<TrackGeometry> bends,
                                       NativeDiamondLayout.SlipEnds ends, double scale) {
        var controls = new ArrayList<Control>();
        var cuts = new ArrayList<Cut>();
        double toe = scale;
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
            double arcEnd = heel(bend, scale, INNER_ARC_EXTENSION);
            double heel = heel(bend, scale, INNER_ARC_EXTENSION + INNER_FLEX_EXTENSION);
            var directedMain = reverse ? main.reversed() : main;
            var directedBend = reverse ? bend.reversed() : bend;
            double transition = transitionStation(directedMain,directedBend.sample(0),
                    station(main,acute,firstLine,reverse),scale);
            var port = new Port(directedMain, other, directedBend, firstLine, reverse, side, transition,
                    station(main, innerCrossing, firstLine, reverse), station(main, outerCrossing, firstLine, reverse),
                    station(other, innerCrossing, !firstLine, false),innerCrossing.workingPoint());
            if (bend.getLength() <= 2 * heel) throw space("inner_blade_heels", CONTROLS.get(i), side, toe, bend.getLength(), 2 * heel);
            controls.add(new Control(CONTROLS.get(i), blades(port, forwardSide, cuts, scale, toe, arcEnd, heel)));
        }
        var gapsByRail = new ArrayList<List<Gap>>(4);
        for (int i = 0; i < 4; i++) {
            int rail = i;
            var main = i < 2 ? first : second;
            int side = i % 2 == 0 ? -1 : 1;
            var gaps = cuts.stream().filter(c -> c.rail() == rail).map(Cut::gap)
                    .sorted(Comparator.comparingDouble(Gap::start)).toList();
            double previousEnd = 0;
            for (var gap : gaps) {
                if (gap.start() < previousEnd || gap.end() > main.getLength())
                    throw space("main_rail_cuts", "through", side, previousEnd, gap.start(), gap.end());
                previousEnd = gap.end();
            }
            gapsByRail.add(gaps);
        }
        return new Blades(List.copyOf(controls),gapsByRail);
    }

    private static Gap frogGap(NativeDiamondLayout.SlipFrogRail frog, double scale) {
        var tail = frog.tail();
        var wing = frog.wing();
        return tail.distance() < wing.distance()
                ? new Gap(tail.distance(),wing.distance(),at(tail,scale),at(wing,scale))
                : new Gap(wing.distance(),tail.distance(),at(wing,scale),at(tail,scale));
    }

    private static Section at(NativeDiamondLayout.RailEnd rail, double scale) {
        var tangent = new TrackVector(Math.sin(rail.yaw()),0,Math.cos(rail.yaw()));
        return new Section(add(rail.point(),lateral(tangent),-rail.side()*HEAD_WIDTH*.5*scale),tangent,HEAD_WIDTH);
    }

    private static List<Blade> blades(Port port, int forwardSide, List<Cut> cuts, double scale, double toe,
                                      double arcEnd, double heel) {
        var main = port.main();
        var bend = port.bend();
        int side = port.side();
        double offset = NativeTurnoutLayout.RAIL_WORK_X * scale;
        double outerHeel = port.outerCrossing() - .90 * scale;
        double pivot = port.innerCrossing() - .75 * scale;
        // Move the heel toward the approach, using at most half of the room before the transition.
        pivot -= Math.clamp((pivot - port.transition()) / 2, 0, .75 * scale);
        double nose = port.innerCrossing() - .04 * scale;
        double crossingEnd = port.innerCrossing();
        if (outerHeel <= port.transition() || pivot <= port.transition() || crossingEnd >= main.getLength())
            throw space("blade_room", "through", side, port.transition(), Math.min(outerHeel, pivot), crossingEnd);

        double stockToe = aligned(main, port.transition() + toe, side * offset,
                edge(bend, toe, side * offset), bend.sample(toe).tangent());
        var start = edge(main, stockToe, side * offset);
        var root = at(bend, arcEnd, side * offset);
        var chord = add(root.point(), start, -1);
        double bearing = yaw(chord);
        double delta = yaw(root.tangent())-bearing;
        double halfTurn = Math.atan2(Math.sin(delta), Math.cos(delta));
        double slipLength = length(chord)/sinc(halfTurn);
        DoubleFunction<Section> slip = s -> {
            // The closed toe arc meets the existing inner curved rail tangentially.
            double t = s/slipLength;
            var along = heading(bearing+(t-1)*halfTurn);
            var direction = heading(bearing+(2*t-1)*halfTurn);
            var horizontal = add(start, along, s*sinc(t*halfTurn));
            var point = new TrackVector(horizontal.x(), start.y()+t*chord.y(), horizontal.z());
            double available = availableWidth(point, direction, side, main, stockToe+s, side*offset, scale);
            double relief = INNER_FOOT_RELIEF*(1-Math.clamp((s/scale-INNER_TOE_TRIM-.06)/.10,0,1));
            return new Section(point,direction,taperWidth(available),lateral(direction),footLimit(available),0,relief);
        };
        DoubleFunction<Section> through = s -> {
            var frame = main.sample(port.transition() + s);
            return trimmed(add(frame.position(), frame.lateral(), -side * offset), frame.tangent(),
                    -side, bend, s, -side * offset, scale);
        };
        double blueLength = outerHeel - port.transition();
        if (slip.apply(slipLength).width() < HEAD_WIDTH - .0001 || through.apply(blueLength).width() < HEAD_WIDTH - .0001)
            throw space("blade_heel_width", "slip", side, 0, blueLength, HEAD_WIDTH);
        addGap(cuts,port,-side,port.transition(),outerHeel,null);
        addGap(cuts,port,side,pivot,crossingEnd,crossingJoint(port));

        var inner = flexible(slip, slipLength, side, true, scale);
        return List.of(extendSlipHeel(inner, port, arcEnd, heel, stockToe+slipLength, scale),
                flexible(through, blueLength, -side, false, scale),
                shortRail(port,nose,pivot,side*offset,forwardSide*offset,scale));
    }

    private static Blade extendSlipHeel(Blade blade, Port port, double from, double to, double stockStation, double scale) {
        if (to == from) return blade;
        var links = new ArrayList<>(blade.links());
        var previous = links.getLast().strip().end();
        int segments = (int)Math.ceil((to-from)/(FLEX_SEGMENT_LENGTH*scale));
        int side = port.side();
        double offset = side*NativeTurnoutLayout.RAIL_WORK_X*scale;
        for (int i = 1; i <= segments; i++) {
            double station = i == segments ? to : from+(to-from)*i/segments;
            var section = at(port.bend(),station,offset);
            // Fit the foot beside the stock rail while preserving the running face.
            double available = availableWidth(section.point(),section.tangent(),side,port.main(),
                    stockStation+station-from,offset,scale);
            var next = new Section(section.point(),section.tangent(),section.width(),section.across(),
                    footLimit(available),0);
            // Keep the original toe travel as the reference before distributing it over the longer blade.
            links.add(new Link(new Strip(previous,next,side),0,0));
            previous = next;
        }
        return new Blade(List.copyOf(links),blade.opensInNormal());
    }

    private static double sinc(double angle) { return angle == 0 ? 1 : Math.sin(angle)/angle; }

    private static TrackVector heading(double yaw) { return new TrackVector(Math.sin(yaw),0,Math.cos(yaw)); }

    private static Section crossingJoint(Port port) {
        var tangent = port.main().sample(port.innerCrossing()).tangent();
        var other = port.other().sample(port.otherCrossing()).tangent();
        var bisector = unit(add(tangent,other,Math.copySign(1,dot(tangent,other))));
        var across = lateral(bisector);
        // Both rails close on one bisector plane with their original running-face tangents.
        return new Section(port.crossingPoint(),tangent,HEAD_WIDTH,multiply(across,1/dot(across,lateral(tangent))),FOOT_OUTER,0);
    }

    private static Blade shortRail(Port port, double nose, double pivot, double offset, double otherOffset, double scale) {
        int side = -port.side();
        var tip = shortSection(port,nose,side,offset,otherOffset,scale,.60);
        var atPivot = port.main().sample(pivot);
        var rootPoint = add(atPivot.position(),atPivot.lateral(),offset);
        double length = length(add(rootPoint,tip.point(),-1));
        double opening = SHORT_RAIL_OPENING * scale;
        if (length <= opening) throw space("short_rail_travel","slip",side,0,length,opening);
        double rotation = side*Math.asin(opening/length);
        var rootTangent = multiply(atPivot.tangent(),-1);
        // The inner lip of the foot passes the fixed heel as the blade opens.
        var root = new Section(rootPoint,rootTangent,HEAD_WIDTH,lateral(rootTangent),FOOT_OUTER,-Math.tan(Math.abs(rotation)));
        var links = new ArrayList<Link>();
        int segments = (int)Math.ceil((nose-pivot)/(.10*scale));
        var previous = tip;
        for (int i = 1; i <= segments; i++) {
            double station = nose+(pivot-nose)*i/segments;
            var next = i == segments ? root : shortSection(port,station,side,offset,otherOffset,scale,0);
            // Equal rotations make all links rotate as one rigid rail around the heel.
            links.add(new Link(new Strip(previous,next,side),rotation,0));
            previous = next;
        }
        return new Blade(List.copyOf(links),false);
    }

    private static Section shortSection(Port port, double station, int side, double offset, double otherOffset,
                                         double scale, double bevel) {
        var pose = port.main().sample(station);
        var point = add(pose.position(),pose.lateral(),offset);
        var tangent = multiply(pose.tangent(),-1);
        double available = availableWidth(point,tangent,side,port.other(),port.otherCrossing(),otherOffset,scale);
        // The crossing rail's foot extends past its working face into the nose's swept area.
        return new Section(point,tangent,taperWidth(available),lateral(tangent),footLimit(available),bevel);
    }

    private static void addGap(List<Cut> cuts, Port port, int side, double start, double end, Section joint) {
        double length = port.main().getLength();
        int forwardSide = port.reverse() ? -side : side;
        int rail = (port.first() ? 0 : 2) + (forwardSide < 0 ? 0 : 1);
        cuts.add(new Cut(rail,port.reverse()
                ? new Gap(length-end,length-start,joint == null ? null : joint.reversed(),null)
                : new Gap(start,end,null,joint)));
    }

    private static double station(TrackGeometry path, NativeDiamondLayout.SlipEnd junction, boolean first, boolean reverse) {
        double station = first ? junction.firstStation() : junction.secondStation();
        return reverse ? path.getLength() - station : station;
    }

    private static Blade flexible(DoubleFunction<Section> section, double length, int side, boolean slip, double scale) {
        int flexCount = Math.max(3, (int)Math.ceil(length/(FLEX_SEGMENT_LENGTH*scale)));
        double target = FLEX_BLADE_OPENING * scale;
        while (true) {
            var strips = new ArrayList<Strip>();
            var weights = new ArrayList<Double>();
            var previous = section.apply(0);
            for (int i = 1; i <= flexCount; i++) {
                var next = section.apply(length*i/flexCount);
                strips.add(new Strip(previous, next, side));
                double u = (i-.5)/flexCount;
                // The linear inner-blade profile establishes the baseline toe travel.
                weights.add(slip ? 1-u : 1-u*u*u*(10+u*(-15+6*u)));
                previous = next;
            }
            double low = 0, high = .25;
            if (travel(strips, weights, high*side, side) < target)
                throw space("blade_travel", "slip", side, 0, length, target);
            for (int i = 0; i < 30; i++) {
                double test = (low+high)/2;
                if (travel(strips,weights,test*side,side) < target) low = test;
                else high = test;
            }
            int required = (int)Math.ceil((slip ? 1 : 1.875)*high/MAX_JOINT_ROTATION);
            if (required > flexCount) { flexCount = required; continue; }
            var links = new ArrayList<Link>(strips.size());
            // Clip the visible toe after solving the full motion, and keep the entire heel link.
            double trim = (slip ? INNER_TOE_TRIM : OUTER_TOE_TRIM)*scale;
            for (int i = 0; i < strips.size(); i++) {
                var strip = strips.get(i);
                float visibleFrom = 0;
                if (trim > 0 && i+1 < strips.size()) {
                    double segmentLength = length(add(strip.end().point(),strip.start().point(),-1));
                    double removed = Math.min(trim,segmentLength);
                    visibleFrom = (float)(removed/segmentLength);
                    trim -= removed;
                }
                links.add(new Link(strip,side*high*weights.get(i),visibleFrom));
            }
            return new Blade(List.copyOf(links),slip);
        }
    }

    private static double travel(List<Strip> strips, List<Double> weights, double rotation, int side) {
        var lateral = lateral(strips.getFirst().start().tangent());
        double opening = 0;
        for (int i = 0; i < strips.size(); i++) {
            var strip = strips.get(i);
            var chord = add(strip.end().point(), strip.start().point(), -1);
            double angle = yaw(chord) + rotation*weights.get(i);
            opening += dot(add(chord, heading(angle), -length(chord)), lateral);
        }
        return -side*opening;
    }

    /** Preserve the opening at the retained toe and distribute more of it along the through blade. */
    public static Blade spreadThroughOpening(Blade blade) {
        var links = blade.links();
        var toe = links.stream().filter(link -> link.visibleFrom() < 1).findFirst().orElseThrow();
        var normal = lateral(unit(add(toe.strip().end().point(),toe.strip().start().point(),-1)));
        int count = links.size();
        var projection = OpeningProjection.of(links,normal);
        double[] weights = new double[count];
        double sign = Math.signum(toe.rotation());
        for (int i = 0; i < count; i++) {
            double u = (i+.5)/count;
            weights[i] = sign*(1-u*u)*(1-Math.pow(u,12));
        }
        // Bound the new profile at the existing segment count, including its fixed heel.
        double maxStep = Math.abs(weights[count-1]);
        for (int i = 1; i < count; i++) maxStep = Math.max(maxStep,Math.abs(weights[i]-weights[i-1]));
        double angle = projection.solve(weights,projection.original(links),sign,Math.min(.25,MAX_JOINT_ROTATION/maxStep));
        return Double.isNaN(angle) ? blade : new Blade(rotatedLinks(links,weights,angle),blade.opensInNormal());
    }

    private static Blade spreadSlipOpening(Blade blade) {
        var links = blade.links();
        var toe = links.stream().filter(link -> link.visibleFrom() < 1).findFirst().orElseThrow();
        var normal = lateral(unit(add(toe.strip().end().point(),toe.strip().start().point(),-1)));
        double sign = Math.signum(toe.rotation());
        double target = OpeningProjection.of(links,normal).original(links);
        double length = links.stream().mapToDouble(NativeDoubleSlipLayout::segmentLength).sum();
        while (true) {
            double station = 0;
            var weights = new double[links.size()];
            for (int i = 0; i < weights.length; i++) {
                double span = segmentLength(links.get(i));
                double u = (station+span/2)/length;
                // Rearward bias with zero slope at the fixed heel.
                weights[i] = sign*(1-u*u*u)*(1-Math.pow(u,12));
                station += span;
            }
            double angle = OpeningProjection.of(links,normal).solve(weights,target,sign,.25);
            if (Double.isNaN(angle)) return blade;
            var refined = new ArrayList<Link>(links.size());
            for (int i = 0; i < weights.length; i++) {
                double before = i == 0 ? weights[i] : weights[i-1];
                double after = i+1 == weights.length ? 0 : weights[i+1];
                var link = links.get(i);
                if (angle*Math.max(Math.abs(weights[i]-before),Math.abs(weights[i]-after)) <= MAX_JOINT_ROTATION) {
                    refined.add(link);
                    continue;
                }
                // Subdivide the existing closed chord and carry its exact toe cut into the two halves.
                var strip = link.strip();
                var middle = strip.start().interpolate(strip.end(),.5);
                refined.add(new Link(new Strip(strip.start(),middle,strip.side()),0,Math.min(1,2*link.visibleFrom())));
                refined.add(new Link(new Strip(middle,strip.end(),strip.side()),0,Math.max(0,2*link.visibleFrom()-1)));
            }
            if (refined.size() == links.size())
                return new Blade(rotatedLinks(links,weights,angle),blade.opensInNormal());
            links = refined;
        }
    }

    private static double segmentLength(Link link) {
        return length(add(link.strip().end().point(),link.strip().start().point(),-1));
    }

    private static List<Link> rotatedLinks(List<Link> links, double[] weights, double angle) {
        var spread = new ArrayList<Link>(links.size());
        for (int i = 0; i < links.size(); i++) {
            var link = links.get(i);
            spread.add(new Link(link.strip(),angle*weights[i],link.visibleFrom()));
        }
        return List.copyOf(spread);
    }

    private record OpeningProjection(double[] along, double[] across) {
        static OpeningProjection of(List<Link> links, TrackVector normal) {
            var along = new double[links.size()]; var across = new double[links.size()];
            for (int i = 0; i < links.size(); i++) {
                var link = links.get(i);
                var chord = add(link.strip().end().point(),link.strip().start().point(),-1);
                along[i] = (1-link.visibleFrom())*dot(chord,normal);
                across[i] = (1-link.visibleFrom())*dot(lateral(chord),normal);
            }
            return new OpeningProjection(along,across);
        }

        double original(List<Link> links) {
            double displacement = 0;
            for (int i = 0; i < along.length; i++) displacement += opening(along[i],across[i],links.get(i).rotation());
            return displacement;
        }

        double solve(double[] weights, double target, double sign, double high) {
            if (sign*(opening(weights,high)-target) < 0) return Double.NaN;
            double low = 0;
            for (int i = 0; i < 30; i++) {
                double mid = (low+high)/2;
                if (sign*(opening(weights,mid)-target) < 0) low = mid;
                else high = mid;
            }
            return high;
        }

        private double opening(double[] weights, double angle) {
            double displacement = 0;
            for (int i = 0; i < along.length; i++) displacement += opening(along[i],across[i],angle*weights[i]);
            return displacement;
        }

        private static double opening(double along, double across, double angle) {
            return along*(Math.cos(angle)-1)+across*Math.sin(angle);
        }
    }

    private static void fixed(List<Strip> strips, TrackGeometry path, double from, double to, int side, double scale) {
        fixed(strips,path,from,to,side,scale,null,null);
    }

    private static void fixed(List<Strip> strips, TrackGeometry path, double from, double to, int side, double scale,
                              Section start, Section end) {
        if (to-from < 1e-6*scale) return;
        double offset = side*NativeTurnoutLayout.RAIL_WORK_X*scale;
        var previous = start == null ? at(path,from,offset) : start;
        var last = end == null ? at(path,to,offset) : end;
        var curve = NativeFixedRails.reference(path,from,to,offset,previous.point(),last.point(),
                yaw(previous.tangent()),yaw(last.tangent()),scale);
        var cuts = NativeFixedRails.cuts(curve,scale);
        for (int i = 1; i < cuts.size(); i++) {
            var sample = curve.at(cuts.get(i));
            var next = i+1 == cuts.size() ? last : new Section(sample.point(),unit(sample.first()),HEAD_WIDTH);
            strips.add(new Strip(previous,next,side)); previous = next;
        }
    }

    private static Section at(TrackGeometry path, double station, double offset) {
        var pose = path.sample(station);
        return new Section(add(pose.position(),pose.lateral(),offset),pose.tangent(),HEAD_WIDTH);
    }

    private static Section trimmed(TrackVector point, TrackVector direction, int side, TrackGeometry face,
                                   double guess, double offset, double scale) {
        double available = availableWidth(point,direction,side,face,guess,offset,scale);
        return new Section(point,direction,taperWidth(available));
    }

    private static double availableWidth(TrackVector point, TrackVector direction, int side, TrackGeometry face,
                                         double guess, double offset, double scale) {
        double station = aligned(face, guess, offset, point, direction);
        double available = side * dot(add(edge(face,station,offset),point,-1), lateral(direction)) / scale;
        if (available < -.00025) throw space("railhead_clearance", "slip", side, guess, available, HEAD_WIDTH);
        return available;
    }

    private static double taperWidth(double available) {
        double blend = Math.max(0, 1-Math.abs(available-HEAD_WIDTH)/.008);
        double width = Math.min(available, HEAD_WIDTH) - .008*blend*blend*blend/6;
        return Math.max(0,width);
    }

    private static double footLimit(double available) {
        return Math.clamp(available-FOOT_INNER-.004,-FOOT_INNER,FOOT_OUTER);
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

    static double transitionStation(TrackGeometry inwardMain, TrackPose bendEnd, double acute, double scale) {
        double station = aligned(inwardMain,acute,0,bendEnd.position(),bendEnd.tangent());
        double tolerance = .00001*scale;
        double inset = station-acute;
        if (Math.abs(inset) <= tolerance) station = acute;
        var main = inwardMain.sample(station);
        var delta = add(main.position(),bendEnd.position(),-1);
        if (inset < -tolerance || inset > NativeDoubleSlipSolver.MAX_INSET*scale+tolerance
                || length(delta) > tolerance || Math.abs(delta.y()) > 1e-6
                || dot(main.tangent(),bendEnd.tangent()) < 1-1e-6)
            throw space("double_slip_transition", "slip", 0, station, inset, NativeDoubleSlipSolver.MAX_INSET*scale);
        return station;
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
