/* Copyright (c) 2026 HabQi; SPDX-License-Identifier: GPL-3.0-only */
package com.unrealtrainmod.track.construction;

import com.unrealtrainmod.network.payload.TrackConstructionPayload.Feedback;
import com.unrealtrainmod.track.AssemblyPlacementGeometry;
import com.unrealtrainmod.track.TrackCurveParameters;
import com.unrealtrainmod.track.TrackPlacementEndpoint;
import com.unrealtrainmod.track.TrackPlacementGeometry;
import com.unrealtrainmod.track.assembly.TrackAssemblyDefinition.Geometry;
import com.unrealtrainmod.track.geometry.TrackVector;
import com.unrealtrainmod.track.mesh.NativeCrossoverLayout;
import com.unrealtrainmod.track.mesh.NativeDiamondLayout;
import com.unrealtrainmod.track.mesh.NativeDoubleSlipLayout;
import com.unrealtrainmod.track.mesh.NativeDoubleSlipSolver;
import com.unrealtrainmod.track.mesh.NativeSingleCrossoverSolver;
import com.unrealtrainmod.track.mesh.NativeTurnoutLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Orders endpoints and validates immutable geometry without accessing the track graph or world. */
final class TurnoutPlacementSolver {
    private static final Comparator<Endpoint> POSITION = Comparator
            .comparingDouble((Endpoint e) -> e.position().x()).thenComparingDouble(e -> e.position().z());
    private static final Comparator<Order> ORDER = Comparator.comparingInt(Order::topologyPenalty)
            .thenComparingDouble(Order::tangentPenalty).thenComparingDouble(Order::shapePenalty)
            .thenComparing(Order::ports, TurnoutPlacementSolver::comparePorts);

    record Endpoint(TrackPlacementEndpoint point, TrackVector inward, TrackCurveParameters saved, TrackCurveParameters manual) {
        TrackVector position() { return point.position(); }
    }

    record Layout(List<TrackPlacementEndpoint> points, List<TrackCurveParameters> curves, List<Boolean> edited,
                  Geometry geometry, boolean adjusted) {
        Layout { curves = Collections.unmodifiableList(new ArrayList<>(curves)); }
    }

    record Result(Layout primary, Layout alternate, Feedback feedback) {}

    private record Order(List<Endpoint> ports, int topologyPenalty, double tangentPenalty, double shapePenalty) {}
    private record Attempt(Layout layout, Feedback feedback) {
        boolean found() { return layout != null && layout.geometry() != null; }
    }

    private TurnoutPlacementSolver() {}

    static Result solve(TrackConstructionType kind, boolean nativeModel, double gauge, List<Endpoint> points,
                        List<TrackPlacementEndpoint> preferred, BooleanSupplier cancelled) {
        if (!preferred.isEmpty()) {
            var ports = preferred.stream().map(point -> points.stream().filter(e -> e.point().equals(point))
                    .findFirst().orElseThrow()).toList();
            var primary = attempt(kind, nativeModel, gauge, ports, cancelled);
            var alternate = kind == TrackConstructionType.SINGLE_CROSSOVER
                    ? attempt(kind, nativeModel, gauge, otherDiagonal(ports), cancelled) : null;
            return new Result(primary.layout(), alternate != null && alternate.found() ? alternate.layout() : null,
                    primary.feedback());
        }

        var sorted = points.stream().sorted(POSITION).toList();
        var orders = kind == TrackConstructionType.SIMPLE_TURNOUT ? turnoutOrders(sorted) : mainLineOrders(kind, sorted);
        Attempt firstFailure = null;
        for (var order : orders) {
            var ports = order.ports();
            if (kind == TrackConstructionType.SINGLE_CROSSOVER) {
                var other = otherDiagonal(ports);
                if (diagonalPenalty(other) < diagonalPenalty(ports)) {
                    var first = ports;
                    ports = other;
                    other = first;
                }
                var first = attempt(kind, nativeModel, gauge, ports, cancelled);
                var second = attempt(kind, nativeModel, gauge, other, cancelled);
                if (first.found()) return new Result(first.layout(), second.found() ? second.layout() : null, Feedback.READY);
                if (second.found()) return new Result(second.layout(), null, Feedback.READY);
                if (firstFailure == null) firstFailure = first;
            } else {
                var candidate = attempt(kind, nativeModel, gauge, ports, cancelled);
                if (candidate.found()) return new Result(candidate.layout(), null, Feedback.READY);
                if (firstFailure == null) firstFailure = candidate;
                if (kind == TrackConstructionType.SIMPLE_TURNOUT) {
                    var reversedBranches = List.of(ports.get(0), ports.get(2), ports.get(1));
                    var reversed = attempt(kind, nativeModel, gauge, reversedBranches, cancelled);
                    if (reversed.found()) return new Result(reversed.layout(), null, Feedback.READY);
                }
            }
        }
        return new Result(firstFailure.layout(), null, firstFailure.feedback());
    }

    private static List<Order> turnoutOrders(List<Endpoint> points) {
        var orders = new ArrayList<Order>();
        for (var toe : points) {
            var branches = points.stream().filter(point -> point != toe).toList();
            var b = branches.getFirst();
            var c = branches.getLast();
            if (compareBranches(toe, b, c) > 0) {
                b = branches.getLast();
                c = branches.getFirst();
            }
            double separation = 1 - dot(direction(toe, b), direction(toe, c));
            double tangent = tangentPenalty(toe, b) + tangentPenalty(toe, c)
                    + tangentPenalty(b, toe) + tangentPenalty(c, toe);
            orders.add(new Order(List.of(toe, b, c), 0, tangent, separation));
        }
        orders.sort(ORDER);
        return orders;
    }

    private static int compareBranches(Endpoint toe, Endpoint b, Endpoint c) {
        int alignment = Double.compare(tangentPenalty(toe, b), tangentPenalty(toe, c));
        return alignment != 0 ? alignment : Double.compare(distance(toe, c), distance(toe, b));
    }

    private static List<Order> mainLineOrders(TrackConstructionType kind, List<Endpoint> points) {
        boolean crossing = kind == TrackConstructionType.DIAMOND_CROSSING || kind == TrackConstructionType.DOUBLE_SLIP;
        var orders = new ArrayList<Order>();
        var a = points.getFirst();
        for (int i = 1; i < points.size(); i++) {
            var b = points.get(i);
            var remaining = points.subList(1, points.size()).stream().filter(point -> point != b).toList();
            var c = remaining.getFirst();
            var d = remaining.getLast();
            double alignment = dot(direction(a, b), direction(c, d));
            if (alignment < 0) {
                c = remaining.getLast();
                d = remaining.getFirst();
            }
            int topology = crosses(a, b, c, d) == crossing ? 0 : 1;
            double tangent = tangentPenalty(a, b) + tangentPenalty(b, a) + tangentPenalty(c, d) + tangentPenalty(d, c);
            double shape = -Math.min(distance(a, b), distance(c, d)) * (.75 + .25 * Math.abs(alignment));
            if (kind == TrackConstructionType.SCISSORS_CROSSOVER) {
                topology += crosses(a, d, c, b) ? 0 : 1;
            }
            orders.add(new Order(List.of(a, b, c, d), topology, tangent, shape));
        }
        orders.sort(ORDER);
        return orders;
    }

    private static List<Endpoint> otherDiagonal(List<Endpoint> p) {
        return List.of(p.get(1), p.get(0), p.get(3), p.get(2));
    }

    private static double diagonalPenalty(List<Endpoint> p) {
        var a = p.get(0);
        var b = p.get(1);
        var c = p.get(2);
        var d = p.get(3);
        return 2 - dot(direction(a, b), direction(a, d)) - dot(direction(d, c), direction(d, a));
    }

    private static Attempt attempt(TrackConstructionType kind, boolean nativeModel, double gauge,
                                   List<Endpoint> ports, BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) throw new CancellationException();
        var points = ports.stream().map(Endpoint::point).toList();
        var positions = ports.stream().map(Endpoint::position).toList();
        var edited = ports.stream().map(port -> port.manual() != null).toList();
        var curves = new ArrayList<TrackCurveParameters>(ports.size());
        for (int i = 0; i < ports.size(); i++) {
            var port = ports.get(i);
            var partner = ports.get(AssemblyPlacementGeometry.partner(kind, i));
            curves.add(curve(port, partner));
        }
        Feedback feedback;
        try {
            if (nativeModel && kind == TrackConstructionType.SINGLE_CROSSOVER) {
                var constraints = ports.stream().map(port -> new NativeSingleCrossoverSolver.Constraint(
                        port.manual() != null || port.point().nodeId() != null, port.manual() != null)).toList();
                for (var port : ports) {
                    if (port.manual() != null && port.inward() != null
                            && Math.cos(Math.toRadians(port.manual().angle() - yaw(port.inward()))) < 1 - 1e-6) {
                        throw new IllegalArgumentException("Manual handle must preserve the connected rail direction");
                    }
                }
                var solved = NativeSingleCrossoverSolver.solve(new NativeSingleCrossoverSolver.Input(
                        positions, gauge, curves, constraints, 0), cancelled);
                if (solved.found()) {
                    return new Attempt(new Layout(points, solved.curves(), edited, solved.geometry(), solved.adjusted()), Feedback.READY);
                }
                feedback = Feedback.NATIVE_CROSSOVER_NO_SOLUTION;
            } else {
                var geometry = AssemblyPlacementGeometry.create(kind, positions, gauge, curves, 0);
                if (nativeModel) validateNative(kind, geometry, gauge);
                AssemblyPlacementGeometry.validate(AssemblyPlacementGeometry.definition(kind), geometry);
                return new Attempt(new Layout(points, curves, edited, geometry, false), Feedback.READY);
            }
        } catch (NativeDoubleSlipSolver.InvalidLayout failure) {
            feedback = switch (failure.problem()) {
                case INTERSECTION -> Feedback.NATIVE_DOUBLE_SLIP_INTERSECTION;
                case ANGLE -> Feedback.NATIVE_DOUBLE_SLIP_ANGLE;
                case SPACE -> Feedback.NATIVE_DOUBLE_SLIP_SPACE;
            };
        } catch (NativeCrossoverLayout.InvalidLayout failure) {
            feedback = switch (failure.problem()) {
                case INTERSECTION -> Feedback.NATIVE_SCISSORS_INTERSECTION;
                case ANGLE -> Feedback.NATIVE_SCISSORS_ANGLE;
                case SPACE -> Feedback.NATIVE_SCISSORS_SPACE;
            };
        } catch (NativeDiamondLayout.InvalidLayout failure) {
            feedback = switch (failure.problem()) {
                case INTERSECTION -> kind == TrackConstructionType.DOUBLE_SLIP
                        ? Feedback.NATIVE_DOUBLE_SLIP_INTERSECTION : Feedback.NATIVE_DIAMOND_INTERSECTION;
                case ANGLE -> kind == TrackConstructionType.DOUBLE_SLIP
                        ? Feedback.NATIVE_DOUBLE_SLIP_ANGLE : Feedback.NATIVE_DIAMOND_ANGLE;
                case SPACE -> kind == TrackConstructionType.DOUBLE_SLIP
                        ? Feedback.NATIVE_DOUBLE_SLIP_SPACE : Feedback.NATIVE_DIAMOND_SPACE;
            };
        } catch (NativeTurnoutLayout.InvalidLayout failure) {
            feedback = failure.problem() == NativeTurnoutLayout.Problem.FROG ? Feedback.NATIVE_FROG : Feedback.NATIVE_SPACE;
        } catch (IllegalArgumentException failure) {
            feedback = Feedback.INVALID_GEOMETRY;
        }
        return new Attempt(new Layout(points, curves, edited, null, false), feedback);
    }

    private static TrackCurveParameters curve(Endpoint port, Endpoint partner) {
        if (port.manual() != null) return port.manual();
        if (port.inward() == null) {
            return port.saved() == null ? null : TrackPlacementGeometry.facing(port.saved(), direction(port, partner));
        }
        double length = port.saved() == null ? TrackPlacementGeometry.defaults(port.position(), partner.position(), 0).length()
                : port.saved().length();
        return new TrackCurveParameters(yaw(port.inward()), length);
    }

    private static void validateNative(TrackConstructionType kind, Geometry geometry, double gauge) {
        switch (kind) {
            case SIMPLE_TURNOUT -> NativeTurnoutLayout.build(geometry, gauge);
            case DIAMOND_CROSSING -> NativeDiamondLayout.build(geometry, gauge);
            case SCISSORS_CROSSOVER -> NativeCrossoverLayout.build(geometry, gauge);
            case DOUBLE_SLIP -> NativeDoubleSlipLayout.build(geometry, gauge);
            case SINGLE_CROSSOVER -> throw new IllegalStateException("Single crossover is validated by its solver");
        }
    }

    private static int comparePorts(List<Endpoint> first, List<Endpoint> second) {
        for (int i = 0; i < first.size(); i++) {
            int position = POSITION.compare(first.get(i), second.get(i));
            if (position != 0) return position;
        }
        return 0;
    }

    private static double tangentPenalty(Endpoint from, Endpoint to) {
        if (from.inward() != null) return 1 - dot(from.inward(), direction(from, to));
        if (from.saved() == null) return 0;
        double angle = Math.toRadians(from.saved().angle());
        return 1 - Math.abs(dot(new TrackVector(-Math.sin(angle), 0, Math.cos(angle)), direction(from, to)));
    }

    private static boolean crosses(Endpoint a, Endpoint b, Endpoint c, Endpoint d) {
        return opposite(side(a, b, c), side(a, b, d)) && opposite(side(c, d, a), side(c, d, b));
    }

    private static boolean opposite(double first, double second) {
        return first > 0 && second < 0 || first < 0 && second > 0;
    }

    private static double side(Endpoint from, Endpoint to, Endpoint point) {
        double dx = to.position().x() - from.position().x();
        double dz = to.position().z() - from.position().z();
        return dx * (point.position().z() - from.position().z()) - dz * (point.position().x() - from.position().x());
    }

    private static TrackVector direction(Endpoint from, Endpoint to) {
        double length = distance(from, to);
        return new TrackVector((to.position().x() - from.position().x()) / length, 0,
                (to.position().z() - from.position().z()) / length);
    }

    private static double distance(Endpoint first, Endpoint second) {
        return Math.hypot(first.position().x() - second.position().x(), first.position().z() - second.position().z());
    }

    private static double dot(TrackVector first, TrackVector second) {
        return first.x() * second.x() + first.z() * second.z();
    }

    private static double yaw(TrackVector direction) {
        return Math.toDegrees(Math.atan2(-direction.x(), direction.z()));
    }
}
