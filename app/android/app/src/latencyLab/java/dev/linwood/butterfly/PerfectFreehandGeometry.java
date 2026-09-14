package dev.linwood.butterfly;

import android.graphics.Path;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.DoubleUnaryOperator;

/**
 * Dependency-free port of {@code perfect_freehand} 2.5.2+1's geometry.
 *
 * <p>The port intentionally keeps all geometry and pressure calculations as {@code double}. Only
 * the final Android {@link Path} calls narrow values to {@code float}, as required by Android's
 * Path API. It mirrors Butterfly's Dart call boundary: thinning and smoothing are limited to
 * [0, 1], streamline is limited to [0.1, 1], and physical size and points are supplied by the
 * caller after any device-pixel-ratio scaling.
 *
 * <p>Upstream source: https://pub.dev/packages/perfect_freehand/versions/2.5.2+1
 *
 * <pre>
 * MIT License
 *
 * Copyright (c) 2021 Stephen Ruiz
 * Copyright (c) 2023 Adil Hanney
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 * </pre>
 */
final class PerfectFreehandGeometry {
    private static final double RATE_OF_PRESSURE_CHANGE = 0.275;
    private static final double PI = Math.PI;
    private static final DoubleUnaryOperator IDENTITY_EASING = t -> t;
    private static final DoubleUnaryOperator START_EASING = t -> t * (2.0 - t);
    private static final DoubleUnaryOperator END_EASING = t -> {
        double value = t - 1.0;
        return value * value * value + 1.0;
    };

    private PerfectFreehandGeometry() {}

    /**
     * Applies the same option limits as {@code PenRenderer._getOutlinePoints} in Butterfly Dart.
     * Size is already in physical pixels at this boundary and is intentionally not clamped.
     */
    static Options butterflyOptions(
            double size,
            double thinning,
            double smoothing,
            double streamline,
            boolean simulatePressure) {
        return butterflyOptions(
                size,
                thinning,
                smoothing,
                streamline,
                simulatePressure,
                false,
                true,
                true,
                null,
                null);
    }

    /**
     * Butterfly call-boundary options with the complete/cap/taper controls needed for parity
     * fixtures. A taper is already in physical pixels; {@code -1} remains the upstream sentinel.
     */
    static Options butterflyOptions(
            double size,
            double thinning,
            double smoothing,
            double streamline,
            boolean simulatePressure,
            boolean isComplete,
            boolean startCap,
            boolean endCap,
            Double startTaper,
            Double endTaper) {
        return new Options(
                size,
                clamp(thinning, 0.0, 1.0),
                clamp(smoothing, 0.0, 1.0),
                clamp(streamline, 0.1, 1.0),
                IDENTITY_EASING,
                simulatePressure,
                EndOptions.start(startCap, false, startTaper),
                EndOptions.end(endCap, false, endTaper),
                isComplete);
    }

    /** Equivalent to perfect_freehand's {@code getStroke}. */
    static List<Point> getStroke(List<Point> points, Options options) {
        return getStroke(points, options, false);
    }

    /** Equivalent to perfect_freehand's {@code getStroke(... rememberSimulatedPressure: ...)}. */
    static List<Point> getStroke(
            List<Point> points, Options options, boolean rememberSimulatedPressure) {
        return getStrokeOutlinePoints(
                getStrokePoints(points, options), options, rememberSimulatedPressure);
    }

    /** Equivalent to perfect_freehand's {@code getStrokePoints}. */
    static List<StrokePoint> getStrokePoints(List<Point> points, Options options) {
        if (points.isEmpty()) {
            return new ArrayList<>();
        }

        final double t = 0.15 + (1.0 - options.streamline) * 0.85;
        List<Point> pts = new ArrayList<>(points.size() + 4);
        for (Point point : points) {
            pts.add(point.withPressure(point.pressure == null ? 0.5 : point.pressure));
        }

        if (pts.size() == 2 && pts.get(0).equalsIncludingPressure(pts.get(1))) {
            pts.remove(1);
        }

        if (pts.size() == 2) {
            Point first = pts.get(0);
            Point last = pts.remove(1);
            for (int i = 1; i < 5; i++) {
                pts.add(first.lerp(((double) i) / 4.0, last));
            }
        }

        if (pts.size() == 1) {
            Point first = pts.get(0);
            pts.add(new Point(first.x + 1.0, first.y + 1.0, first.pressure));
        }

        List<StrokePoint> strokePoints = new ArrayList<>(pts.size());
        StrokePoint first = new StrokePoint(
                pts.get(0),
                new Point(1.0, 1.0, null),
                0.0,
                0.0,
                points,
                0);
        strokePoints.add(first);

        boolean hasReachedMinimumLength = false;
        double runningLength = 0.0;
        StrokePoint previous = first;
        int max = pts.size() - 1;

        for (int i = 0; i < pts.size(); i++) {
            Point point = options.isComplete && i == max
                    ? pts.get(i)
                    : previous.point.lerp(t, pts.get(i));
            if (point.equalsIncludingPressure(previous.point)) {
                continue;
            }

            double distance = point.distanceTo(previous.point);
            runningLength += distance;
            if (i < max && !hasReachedMinimumLength) {
                if (runningLength < options.size) {
                    continue;
                }
                hasReachedMinimumLength = true;
            }

            previous = new StrokePoint(
                    point,
                    point.unitVectorTo(previous.point),
                    distance,
                    runningLength,
                    points,
                    Math.min(i, points.size() - 1));
            strokePoints.add(previous);
        }

        if (strokePoints.size() > 1) {
            strokePoints.get(0).vector = strokePoints.get(1).vector;
        } else {
            strokePoints.get(0).vector = new Point(0.0, 0.0, null);
        }
        return strokePoints;
    }

    /**
     * Equivalent to perfect_freehand's {@code getStrokeOutlinePoints}, including optional storage
     * of simulated pressures back into the caller's mutable point list.
     */
    static List<Point> getStrokeOutlinePoints(
            List<StrokePoint> points, Options options, boolean rememberSimulatedPressure) {
        if (rememberSimulatedPressure && (!options.simulatePressure || !options.isComplete)) {
            throw new IllegalArgumentException(
                    "rememberSimulatedPressure requires simulated pressure and a complete stroke");
        }
        if (points.isEmpty() || options.size <= 0.0) {
            return new ArrayList<>();
        }

        double totalLength = points.get(points.size() - 1).runningLength;
        double taperStart = options.start.taperEnabled
                ? (options.start.customTaper == null
                        ? Math.max(options.size, totalLength)
                        : options.start.customTaper)
                : 0.0;
        double taperEnd = options.end.taperEnabled
                ? (options.end.customTaper == null
                        ? Math.max(options.size, totalLength)
                        : options.end.customTaper)
                : 0.0;
        double minDistance = Math.pow(options.size * options.smoothing, 2.0);

        List<Point> leftPoints = new ArrayList<>();
        List<Point> rightPoints = new ArrayList<>();

        double previousPressure = points.get(0).pressure();
        int pressureStartCount = Math.min(10, points.size() - 1);
        for (int i = 0; i < pressureStartCount; i++) {
            StrokePoint current = points.get(i);
            double pressure = options.simulatePressure
                    ? current.simulatePressure(previousPressure, options.size)
                    : current.pressure();
            previousPressure = (previousPressure + pressure) / 2.0;
        }

        double radius = getStrokeRadius(
                options.size,
                options.thinning,
                points.get(points.size() - 1).pressure(),
                options.easing);
        Double firstRadius = null;
        Point previousVector = points.get(0).vector;
        Point previousLeft = points.get(0).point;
        Point previousRight = previousLeft;
        Point temporaryLeft = previousLeft;
        Point temporaryRight = previousRight;
        boolean isPreviousPointSharpCorner = false;

        for (int i = 0; i < points.size(); i++) {
            StrokePoint strokePoint = points.get(i);
            Point point = strokePoint.point;
            Point vector = strokePoint.vector;
            double runningLength = strokePoint.runningLength;

            if (i < points.size() - 1
                    && options.isComplete
                    && !isPreviousPointSharpCorner
                    && totalLength - runningLength < options.size / 2.0) {
                continue;
            }

            if (options.thinning != 0.0) {
                double pressure;
                if (options.simulatePressure) {
                    pressure = strokePoint.simulatePressure(previousPressure, options.size);
                    if (rememberSimulatedPressure) {
                        strokePoint.updatePressure(pressure);
                    }
                    previousPressure = pressure;
                } else {
                    pressure = strokePoint.pressure();
                }
                radius = getStrokeRadius(options.size, options.thinning, pressure, options.easing);
            } else {
                radius = options.size / 2.0;
            }

            if (firstRadius == null) {
                firstRadius = radius;
            }

            double taperStartStrength = runningLength < taperStart
                    ? options.start.easing.applyAsDouble(runningLength / taperStart)
                    : 1.0;
            double taperEndStrength = totalLength - runningLength < taperEnd
                    ? options.end.easing.applyAsDouble((totalLength - runningLength) / taperEnd)
                    : 1.0;
            radius = Math.max(0.01, radius * Math.min(taperStartStrength, taperEndStrength));

            Point nextVector = i < points.size() - 1 ? points.get(i + 1).vector : vector;
            double nextDpr = i < points.size() - 1 ? vector.dot(nextVector) : 1.0;
            double previousDpr = vector.dot(previousVector);
            double maxDprForSharpCorner = options.size / 128.0;
            boolean isPointSharpCorner = previousDpr < maxDprForSharpCorner
                    && !isPreviousPointSharpCorner;
            boolean isNextPointSharpCorner = nextDpr < maxDprForSharpCorner;

            if (isPointSharpCorner || isNextPointSharpCorner) {
                Point previousOffset = previousVector.perpendicular().times(radius);
                double step = 1.0 / 13.0;
                for (double amount = 0.0; amount <= 1.0; amount += step) {
                    temporaryLeft = point.minus(previousOffset).rotAround(point, PI * amount);
                    leftPoints.add(temporaryLeft);
                    temporaryRight = point.plus(previousOffset).rotAround(point, -PI * amount);
                    rightPoints.add(temporaryRight);
                }

                Point nextOffset = nextVector.perpendicular().times(radius);
                temporaryLeft = point.plus(nextOffset).rotAround(point, -PI);
                temporaryRight = point.minus(nextOffset).rotAround(point, PI);
                leftPoints.add(temporaryLeft);
                rightPoints.add(temporaryRight);
                previousLeft = temporaryRight;
                previousRight = temporaryLeft;
                if (isNextPointSharpCorner) {
                    isPreviousPointSharpCorner = true;
                }
                continue;
            }

            isPreviousPointSharpCorner = false;
            if (i == points.size() - 1) {
                Point offset = vector.perpendicular().times(radius);
                leftPoints.add(point.minus(offset));
                rightPoints.add(point.plus(offset));
                continue;
            }

            Point offset = nextVector.lerp(nextDpr, vector).perpendicular().times(radius);
            temporaryLeft = point.minus(offset);
            if (i <= 1 || previousLeft.distanceSquaredTo(temporaryLeft) > minDistance) {
                leftPoints.add(temporaryLeft);
                previousLeft = temporaryLeft;
            }
            temporaryRight = point.plus(offset);
            if (i <= 1 || previousRight.distanceSquaredTo(temporaryRight) > minDistance) {
                rightPoints.add(temporaryRight);
                previousRight = temporaryRight;
            }
            previousVector = vector;
        }

        Point firstPoint = points.get(0).point;
        Point lastPoint = points.size() > 1
                ? points.get(points.size() - 1).point
                : firstPoint.plus(points.get(0).vector);
        List<Point> startCap = new ArrayList<>();
        List<Point> endCap = new ArrayList<>();

        if (points.size() == 1) {
            if (!(taperStart > 0.0 || taperEnd > 0.0) || options.isComplete) {
                Point start = firstPoint.project(
                        firstPoint.minus(lastPoint).perpendicular().unit(),
                        -(firstRadius == null ? radius : firstRadius));
                List<Point> dotPoints = new ArrayList<>();
                double step = 1.0 / 13.0;
                for (double amount = step; amount <= 1.0; amount += step) {
                    dotPoints.add(start.rotAround(firstPoint, PI * 2.0 * amount));
                }
                return dotPoints;
            }
        } else if (taperStart > 0.0 || (taperEnd > 0.0 && points.size() == 1)) {
            // Tapered start: no cap.
        } else if (options.start.cap) {
            double step = 1.0 / 13.0;
            for (double amount = step; amount <= 1.0; amount += step) {
                startCap.add(rightPoints.get(0).rotAround(firstPoint, PI * amount));
            }
        } else {
            Point cornersVector = leftPoints.get(0).minus(rightPoints.get(0));
            Point offsetA = cornersVector.times(0.5);
            Point offsetB = cornersVector.times(0.51);
            startCap.add(firstPoint.minus(offsetA));
            startCap.add(firstPoint.minus(offsetB));
            startCap.add(firstPoint.plus(offsetB));
            startCap.add(firstPoint.plus(offsetA));
        }

        Point direction = points.get(points.size() - 1).vector.negated().perpendicular();
        if (taperEnd > 0.0 || (taperStart > 0.0 && points.size() == 1)) {
            endCap.add(lastPoint);
        } else if (options.end.cap) {
            Point start = lastPoint.project(direction, radius);
            double step = 1.0 / 29.0;
            for (double amount = step; amount <= 1.0; amount += step) {
                endCap.add(start.rotAround(lastPoint, PI * 3.0 * amount));
            }
        } else {
            endCap.add(lastPoint.plus(direction.times(radius)));
            endCap.add(lastPoint.plus(direction.times(radius * 0.99)));
            endCap.add(lastPoint.minus(direction.times(radius * 0.99)));
            endCap.add(lastPoint.minus(direction.times(radius)));
        }

        List<Point> outline = new ArrayList<>(
                leftPoints.size() + endCap.size() + rightPoints.size() + startCap.size());
        outline.addAll(leftPoints);
        outline.addAll(endCap);
        for (int i = rightPoints.size() - 1; i >= 0; i--) {
            outline.add(rightPoints.get(i));
        }
        outline.addAll(startCap);
        return outline;
    }

    /** Equivalent to perfect_freehand's {@code getStrokeRadius}. */
    static double getStrokeRadius(
            double size, double thinning, double pressure, DoubleUnaryOperator easing) {
        return size * easing.applyAsDouble(0.5 - thinning * (0.5 - pressure));
    }

    /**
     * Builds the same open, filled quadratic path that Butterfly's PenRenderer creates from an
     * outline. It deliberately does not call {@link Path#close()}.
     */
    static Path buildFilledQuadraticPath(List<Point> outlinePoints) {
        return buildFilledQuadraticPath(outlinePoints, new Path());
    }

    /**
     * Same as {@link #buildFilledQuadraticPath(List)} but resets and reuses the caller's
     * {@link Path} instead of allocating a new one every call. Only safe when the caller
     * guarantees the Path is never touched concurrently (e.g. it is confined to a single
     * render-callback thread), since {@code path.reset()} is not itself synchronized.
     */
    static Path buildFilledQuadraticPath(List<Point> outlinePoints, Path path) {
        path.reset();
        if (outlinePoints.isEmpty()) {
            return path;
        }
        if (outlinePoints.size() < 2) {
            Point point = outlinePoints.get(0);
            path.addOval(
                    new RectF(
                            (float) (point.x - 1.0),
                            (float) (point.y - 1.0),
                            (float) (point.x + 1.0),
                            (float) (point.y + 1.0)),
                    Path.Direction.CW);
            return path;
        }

        Point first = outlinePoints.get(0);
        path.moveTo((float) first.x, (float) first.y);
        for (int i = 1; i < outlinePoints.size() - 1; i++) {
            Point control = outlinePoints.get(i);
            Point end = outlinePoints.get(i + 1);
            path.quadTo(
                    (float) control.x,
                    (float) control.y,
                    (float) ((control.x + end.x) / 2.0),
                    (float) ((control.y + end.y) / 2.0));
        }
        return path;
    }

    /**
     * Pure-JVM representation of {@link #buildFilledQuadraticPath(List)}. It is also the fixture
     * topology, allowing exact verification without reading Android Path internals in a local JVM
     * test.
     */
    static List<PathOperation> filledQuadraticPathOperations(List<Point> outlinePoints) {
        if (outlinePoints.isEmpty()) {
            return Collections.emptyList();
        }
        List<PathOperation> operations = new ArrayList<>();
        if (outlinePoints.size() < 2) {
            Point point = outlinePoints.get(0);
            operations.add(PathOperation.oval(point.x, point.y, 1.0));
            return operations;
        }
        Point first = outlinePoints.get(0);
        operations.add(PathOperation.move(first.x, first.y));
        for (int i = 1; i < outlinePoints.size() - 1; i++) {
            Point control = outlinePoints.get(i);
            Point end = outlinePoints.get(i + 1);
            operations.add(PathOperation.quadratic(
                    control.x,
                    control.y,
                    (control.x + end.x) / 2.0,
                    (control.y + end.y) / 2.0));
        }
        return operations;
    }

    private static double clamp(double value, double minimum, double maximum) {
        if (value < minimum) {
            return minimum;
        }
        if (value > maximum) {
            return maximum;
        }
        return value;
    }

    static final class Options {
        final double size;
        final double thinning;
        final double smoothing;
        final double streamline;
        final DoubleUnaryOperator easing;
        final boolean simulatePressure;
        final EndOptions start;
        final EndOptions end;
        final boolean isComplete;

        Options(
                double size,
                double thinning,
                double smoothing,
                double streamline,
                DoubleUnaryOperator easing,
                boolean simulatePressure,
                EndOptions start,
                EndOptions end,
                boolean isComplete) {
            this.size = size;
            this.thinning = thinning;
            this.smoothing = smoothing;
            this.streamline = streamline;
            this.easing = easing;
            this.simulatePressure = simulatePressure;
            this.start = start;
            this.end = end;
            this.isComplete = isComplete;
        }
    }

    static final class EndOptions {
        final boolean cap;
        final boolean taperEnabled;
        final Double customTaper;
        final DoubleUnaryOperator easing;

        private EndOptions(
                boolean cap,
                boolean taperEnabled,
                Double customTaper,
                DoubleUnaryOperator easing) {
            boolean enabled = taperEnabled;
            Double taper = customTaper;
            if (taper != null) {
                enabled = taper != 0.0;
                if (taper == -1.0) {
                    taper = null;
                }
            }
            this.cap = cap;
            this.taperEnabled = enabled;
            this.customTaper = taper;
            this.easing = easing;
        }

        static EndOptions start(boolean cap, boolean taperEnabled, Double customTaper) {
            return new EndOptions(cap, taperEnabled, customTaper, START_EASING);
        }

        static EndOptions end(boolean cap, boolean taperEnabled, Double customTaper) {
            return new EndOptions(cap, taperEnabled, customTaper, END_EASING);
        }
    }

    static final class Point {
        final double x;
        final double y;
        final Double pressure;

        Point(double x, double y, Double pressure) {
            this.x = x;
            this.y = y;
            this.pressure = pressure;
        }

        Point withPressure(Double newPressure) {
            return new Point(x, y, newPressure);
        }

        Point lerp(double amount, Point other) {
            if (!isFinite()) {
                return other;
            }
            if (!other.isFinite()) {
                return this;
            }
            Double thisPressure = pressure == null ? other.pressure : pressure;
            Double otherPressure = other.pressure == null ? pressure : other.pressure;
            return new Point(
                    x * (1.0 - amount) + other.x * amount,
                    y * (1.0 - amount) + other.y * amount,
                    thisPressure == null || otherPressure == null
                            ? null
                            : thisPressure * (1.0 - amount) + otherPressure * amount);
        }

        double distanceSquaredTo(Point other) {
            double dx = x - other.x;
            double dy = y - other.y;
            return dx * dx + dy * dy;
        }

        double distanceTo(Point other) {
            return Math.sqrt(distanceSquaredTo(other));
        }

        Point unitVectorTo(Point other) {
            double dx = other.x - x;
            double dy = other.y - y;
            if (dx == 0.0 && dy == 0.0) {
                return new Point(0.0, 0.0, null);
            }
            double distance = Math.sqrt(dx * dx + dy * dy);
            return new Point(dx / distance, dy / distance, null);
        }

        double dot(Point other) {
            return x * other.x + y * other.y;
        }

        Point perpendicular() {
            return new Point(y, -x, null);
        }

        Point unit() {
            double length = Math.sqrt(x * x + y * y);
            if (length == 0.0) {
                return new Point(0.0, 0.0, null);
            }
            return new Point(x / length, y / length, null);
        }

        Point project(Point direction, double distance) {
            return new Point(x + direction.x * distance, y + direction.y * distance, pressure);
        }

        Point rotAround(Point center, double radians) {
            double sine = Math.sin(radians);
            double cosine = Math.cos(radians);
            double px = x - center.x;
            double py = y - center.y;
            return new Point(
                    px * cosine - py * sine + center.x,
                    px * sine + py * cosine + center.y,
                    pressure);
        }

        Point times(double value) {
            return new Point(x * value, y * value, pressure);
        }

        Point plus(Point other) {
            return new Point(x + other.x, y + other.y, pressure == null ? other.pressure : pressure);
        }

        Point minus(Point other) {
            return new Point(x - other.x, y - other.y, other.pressure == null ? pressure : other.pressure);
        }

        Point negated() {
            return new Point(-x, -y, pressure);
        }

        boolean isFinite() {
            return Double.isFinite(x) && Double.isFinite(y);
        }

        boolean equalsIncludingPressure(Point other) {
            boolean samePressure = pressure == null
                    ? other.pressure == null
                    : other.pressure != null
                            && pressure.doubleValue() == other.pressure.doubleValue();
            return x == other.x && y == other.y && samePressure;
        }
    }

    static final class StrokePoint {
        Point point;
        Point vector;
        final double distance;
        final double runningLength;
        private final List<Point> sourcePoints;
        private final int sourceIndex;

        StrokePoint(
                Point point,
                Point vector,
                double distance,
                double runningLength,
                List<Point> sourcePoints,
                int sourceIndex) {
            this.point = point;
            this.vector = vector;
            this.distance = distance;
            this.runningLength = runningLength;
            this.sourcePoints = sourcePoints;
            this.sourceIndex = sourceIndex;
        }

        double pressure() {
            return point.pressure == null ? 0.5 : point.pressure;
        }

        void updatePressure(double pressure) {
            point = point.withPressure(pressure);
            sourcePoints.set(sourceIndex, sourcePoints.get(sourceIndex).withPressure(pressure));
        }

        double simulatePressure(double previousPressure, double size) {
            double speed = Math.min(1.0, distance / size);
            double rate = Math.min(1.0, 1.0 - speed);
            return Math.min(
                    1.0,
                    previousPressure
                            + (rate - previousPressure) * (speed * RATE_OF_PRESSURE_CHANGE));
        }
    }

    static final class PathOperation {
        enum Kind {
            OVAL,
            MOVE,
            QUADRATIC
        }

        final Kind kind;
        final double[] values;

        private PathOperation(Kind kind, double... values) {
            this.kind = kind;
            this.values = values;
        }

        static PathOperation oval(double centerX, double centerY, double radius) {
            return new PathOperation(Kind.OVAL, centerX, centerY, radius);
        }

        static PathOperation move(double x, double y) {
            return new PathOperation(Kind.MOVE, x, y);
        }

        static PathOperation quadratic(double controlX, double controlY, double endX, double endY) {
            return new PathOperation(Kind.QUADRATIC, controlX, controlY, endX, endY);
        }
    }
}
