package dev.linwood.butterfly;

import android.graphics.Path;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Dependency-free port of {@code perfect_freehand} 2.5.2+1's geometry, restricted to the exact
 * options Butterfly's {@code PenRenderer} ever calls it with: {@code isComplete=false}, both
 * caps enabled, no taper, identity easing (see {@link #butterflyOptions}). The port intentionally
 * keeps all geometry and pressure calculations as {@code double}. Only the final Android
 * {@link Path} calls narrow values to {@code float}, as required by Android's Path API. It
 * mirrors Butterfly's Dart call boundary: thinning and smoothing are limited to [0, 1],
 * streamline is limited to [0.1, 1], and physical size and points are supplied by the caller
 * after any device-pixel-ratio scaling.
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

    private PerfectFreehandGeometry() {}

    /**
     * Butterfly's fixed call-boundary options: the same limits as {@code
     * PenRenderer._getOutlinePoints} in Butterfly Dart, plus perfect_freehand's own defaults for
     * everything Butterfly never overrides (isComplete false, both caps enabled, no taper,
     * identity easing). Size is already in physical pixels at this boundary and is intentionally
     * not clamped.
     */
    static Options butterflyOptions(
            double size,
            double thinning,
            double smoothing,
            double streamline,
            boolean simulatePressure) {
        return new Options(
                size,
                clamp(thinning, 0.0, 1.0),
                clamp(smoothing, 0.0, 1.0),
                clamp(streamline, 0.1, 1.0),
                simulatePressure);
    }

    /** Equivalent to perfect_freehand's {@code getStroke}. */
    static List<Point> getStroke(List<Point> points, Options options) {
        return getStrokeOutlineParts(getStrokePoints(points, options), options).assemble();
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
        StrokePoint first = new StrokePoint(pts.get(0), new Point(1.0, 1.0, null), 0.0, 0.0);
        strokePoints.add(first);

        boolean hasReachedMinimumLength = false;
        double runningLength = 0.0;
        StrokePoint previous = first;
        int max = pts.size() - 1;

        for (int i = 0; i < pts.size(); i++) {
            Point point = previous.point.lerp(t, pts.get(i));
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
                    point, point.unitVectorTo(previous.point), distance, runningLength);
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
     * Same computation as {@code getStroke}, but returns the left/right rails and both caps
     * separately instead of the assembled outline: {@code PerfectFreehandGeometryParityTest}
     * uses this to prove {@link IncrementalOutline}'s own rails, built up one append at a time,
     * equal a from-scratch computation exactly. Delegates to a throwaway
     * {@code IncrementalOutline}, replayed over the already-known {@code points}, so the batch
     * case and the truly incremental, append-only case share one implementation of the
     * per-point outline step instead of each carrying its own copy.
     */
    static OutlineParts getStrokeOutlineParts(List<StrokePoint> points, Options options) {
        if (points.isEmpty() || options.size <= 0.0) {
            return OutlineParts.empty();
        }
        IncrementalOutline engine = new IncrementalOutline(
                options.size, options.thinning, options.smoothing, options.streamline);
        engine.replay(points, options.simulatePressure);
        return new OutlineParts(engine.leftPoints, engine.rightPoints, engine.startCap, engine.endCap);
    }

    /**
     * The four pieces {@code getStroke} assembles into one outline: the left rail (in order),
     * the right rail (in order -- callers assembling an outline must walk it backwards), and the
     * start/end caps.
     */
    static final class OutlineParts {
        final List<Point> left;
        final List<Point> right;
        final List<Point> startCap;
        final List<Point> endCap;

        OutlineParts(List<Point> left, List<Point> right, List<Point> startCap, List<Point> endCap) {
            this.left = left;
            this.right = right;
            this.startCap = startCap;
            this.endCap = endCap;
        }

        static OutlineParts empty() {
            return new OutlineParts(
                    new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }

        List<Point> assemble() {
            List<Point> outline =
                    new ArrayList<>(left.size() + endCap.size() + right.size() + startCap.size());
            outline.addAll(left);
            outline.addAll(endCap);
            for (int i = right.size() - 1; i >= 0; i--) {
                outline.add(right.get(i));
            }
            outline.addAll(startCap);
            return outline;
        }
    }

    /**
     * Stateful, append-only engine that is the single implementation of perfect_freehand's
     * per-point outline step ({@link #processPoint}), for exactly the options
     * {@code SharedGeometryInkOverlay} draws with while a stroke is in progress:
     * {@code isComplete=false}, both caps enabled, no taper (see {@link #butterflyOptions}).
     * {@link #getStrokeOutlineParts} drives the same engine, replayed over an already-known
     * point sequence, for the batch case -- so there is only ever one copy of the per-point body
     * rather than a separate one for streaming and one for batch computation. Production feeds
     * this engine one raw sample at a time via {@link #appendPoints} and draws only the returned
     * tail fragment (never clearing) instead of recomputing and redrawing the whole outline every
     * frame.
     *
     * <p>This is safe because, with those options, appending a point only ever changes two
     * things: the outline entries already emitted (tentatively) for the previously-appended
     * point -- finalized once this point supplies its real successor vector -- and the end cap.
     * Every earlier point's entries are final the moment a successor exists and are never
     * revisited. {@code PerfectFreehandGeometryParityTest} proves this by comparing this class's
     * final {@link #leftPointsSnapshot} / {@link #rightPointsSnapshot} against a from-scratch
     * {@link #getStrokeOutlineParts} call on the same points.
     *
     * <p>Two of the underlying algorithm's steps genuinely look ahead and so cannot be
     * appended incrementally from the very first point: the streamline filter
     * ({@link #getStrokePoints}) unconditionally flushes whatever the current last point is, and
     * retroactively reconsiders that decision once a real successor arrives; and the
     * pressure-simulation radius uses a warm-up average over the stroke's first up to 10 points,
     * which keeps changing until that many exist. Both settle for good within a handful of
     * points, so below {@link #SETTLE_STROKE_POINTS} stroke points this class resets and replays
     * its own per-point step over the (cheap, small-N) full recomputation on every call, rather
     * than calling a separate batch function; a stroke that never reaches that many points just
     * keeps paying that cheap cost for its whole (short) lifetime. From
     * {@link #SETTLE_STROKE_POINTS} onward every further point is a true O(1) append.
     */
    static final class IncrementalOutline {
        // Comfortably past both look-ahead windows for any stroke that is not essentially
        // stationary: the pressure warm-up locks at 11 stroke points, and the streamline filter's
        // minimum-length flush is, in practice, resolved within the first few.
        private static final int SETTLE_STROKE_POINTS = 12;
        private static final int OVERLAP = 2;

        private final double size;
        private final double thinning;
        private final double smoothing;
        private final double streamline;
        private final double t;
        private final double minDistance;

        private final List<Point> rawPoints = new ArrayList<>();
        private boolean settled;
        private boolean simulatePressureDecision;

        // getStrokePoints-layer resumable state (valid once settled).
        private Point previousStrokePointPoint;
        private double previousStrokePointRunningLength;

        // getStroke-layer resumable state, as of the last CONFIRMED point.
        private double previousPressure;
        private Point previousVector;
        private Point previousLeft;
        private Point previousRight;
        private boolean isPreviousPointSharpCorner;
        private int pointIndex;
        private double lastRadius;
        private Point firstPointPoint;

        private final List<Point> leftPoints = new ArrayList<>();
        private final List<Point> rightPoints = new ArrayList<>();
        private List<Point> startCap = new ArrayList<>();
        private List<Point> endCap = new ArrayList<>();

        // The most recently appended point's contribution: tentative until a real successor
        // finalizes it (see appendSettledStrokePoint). Cleared/replaced, never both at once.
        private boolean havePending;
        private StrokePoint pendingPoint;
        private int pendingLeftCount;
        private int pendingRightCount;

        // Snapshot of the confirmed running state as of just before `pendingPoint` was
        // processed, so finalizing it can restore exactly that state and redo it for real.
        private double confirmedPressure;
        private Point confirmedVector;
        private Point confirmedLeft;
        private Point confirmedRight;
        private boolean confirmedSharpCorner;
        private int confirmedIndex;

        // Rail lengths as of the last checkpointTail() call, so that call returns only what
        // changed since the *previous* checkpoint rather than the whole stroke (see
        // SharedGeometryInkOverlay.drawCheckpointDelta). Both start at 0, and combined with
        // checkpointStartCapDrawn below that makes the very first call return the full (at that
        // point still small) outline, start cap included.
        private int checkpointLeftBoundary;
        private int checkpointRightBoundary;
        private boolean checkpointStartCapDrawn;

        IncrementalOutline(double size, double thinning, double smoothing, double streamline) {
            this.size = size;
            this.thinning = clamp(thinning, 0.0, 1.0);
            this.smoothing = clamp(smoothing, 0.0, 1.0);
            this.streamline = clamp(streamline, 0.1, 1.0);
            this.t = 0.15 + (1.0 - this.streamline) * 0.85;
            this.minDistance = Math.pow(size * this.smoothing, 2.0);
        }

        /** What changed by appending the given raw samples, and whether to clear first. */
        static final class TailResult {
            final List<Point> polygon;
            final boolean clearFirst;

            TailResult(List<Point> polygon, boolean clearFirst) {
                this.polygon = polygon;
                this.clearFirst = clearFirst;
            }
        }

        TailResult appendPoints(List<Point> newPoints) {
            if (newPoints.isEmpty()) return new TailResult(List.of(), false);
            if (!settled) {
                int consumed = 0;
                List<Point> full = List.of();
                for (; consumed < newPoints.size() && !settled; consumed++) {
                    rawPoints.add(newPoints.get(consumed));
                    full = recomputeUnsettled();
                }
                if (!settled) return new TailResult(full, true);
                for (; consumed < newPoints.size(); consumed++) {
                    appendOnePoint(newPoints.get(consumed));
                }
                return new TailResult(assembleFullOutline(), true);
            }
            int leftBoundary = leftPoints.size() - pendingLeftCount;
            int rightBoundary = rightPoints.size() - pendingRightCount;
            boolean anyChange = false;
            for (Point raw : newPoints) {
                if (appendOnePoint(raw)) anyChange = true;
            }
            return anyChange
                    ? new TailResult(tailSince(leftBoundary, rightBoundary), false)
                    : new TailResult(List.of(), false);
        }

        /**
         * Speculative tail for a predicted (not yet real) sample: computed, then immediately
         * rolled back, so it is drawn once and never affects the persistent outline state. Empty
         * before the engine has a confirmed baseline to extend.
         */
        List<Point> predictTail(Point predicted) {
            if (!settled || !havePending) return List.of();
            int leftSize = leftPoints.size();
            int rightSize = rightPoints.size();
            Point savedStrokePoint = previousStrokePointPoint;
            double savedRunningLength = previousStrokePointRunningLength;
            double savedPressure = previousPressure, savedConfirmedPressure = confirmedPressure;
            Point savedVector = previousVector, savedLeft = previousLeft, savedRight = previousRight;
            Point savedConfirmedVector = confirmedVector, savedConfirmedLeft = confirmedLeft,
                    savedConfirmedRight = confirmedRight;
            boolean savedSharp = isPreviousPointSharpCorner, savedConfirmedSharp = confirmedSharpCorner;
            int savedIndex = pointIndex, savedConfirmedIndex = confirmedIndex;
            StrokePoint savedPending = pendingPoint;
            int savedPendingLeft = pendingLeftCount, savedPendingRight = pendingRightCount;
            List<Point> savedEndCap = endCap;
            double savedRadius = lastRadius;

            int leftBoundary = leftSize - pendingLeftCount;
            int rightBoundary = rightSize - pendingRightCount;
            List<Point> tail = appendOnePoint(predicted)
                    ? tailSince(leftBoundary, rightBoundary)
                    : List.of();

            truncate(leftPoints, leftPoints.size() - leftSize);
            truncate(rightPoints, rightPoints.size() - rightSize);
            previousStrokePointPoint = savedStrokePoint;
            previousStrokePointRunningLength = savedRunningLength;
            previousPressure = savedPressure;
            confirmedPressure = savedConfirmedPressure;
            previousVector = savedVector;
            previousLeft = savedLeft;
            previousRight = savedRight;
            confirmedVector = savedConfirmedVector;
            confirmedLeft = savedConfirmedLeft;
            confirmedRight = savedConfirmedRight;
            isPreviousPointSharpCorner = savedSharp;
            confirmedSharpCorner = savedConfirmedSharp;
            pointIndex = savedIndex;
            confirmedIndex = savedConfirmedIndex;
            pendingPoint = savedPending;
            pendingLeftCount = savedPendingLeft;
            pendingRightCount = savedPendingRight;
            endCap = savedEndCap;
            lastRadius = savedRadius;
            return tail;
        }

        /** Defensive-copy snapshots for parity testing against a from-scratch computation. */
        List<Point> leftPointsSnapshot() { return new ArrayList<>(leftPoints); }
        List<Point> rightPointsSnapshot() { return new ArrayList<>(rightPoints); }

        /** The full current outline, exactly as {@code getStroke} would compute it from scratch
         * on every raw point seen so far -- used for the multi-buffered (checkpoint and
         * completion) draw, which wants the exact whole shape rather than a tail. */
        List<Point> assembleFullOutline() {
            List<Point> outline = new ArrayList<>(
                    leftPoints.size() + endCap.size() + rightPoints.size() + startCap.size());
            outline.addAll(leftPoints);
            outline.addAll(endCap);
            for (int i = rightPoints.size() - 1; i >= 0; i--) outline.add(rightPoints.get(i));
            outline.addAll(startCap);
            return outline;
        }

        /**
         * The outline fragment appended since the previous call to this method (or since the
         * engine's first settled point, on the very first call), padded the same way
         * {@link #tailSince} pads a front-buffer tail so a checkpoint's redraw shares an edge
         * with -- rather than merely touches -- whatever the persistent multi-buffered layer
         * already holds from the previous checkpoint. Advances the recorded boundary to the
         * rails' current confirmed length, so the next call only returns what changed since
         * *this* call: cost is bounded by one checkpoint interval's geometry, not the whole
         * stroke.
         *
         * <p>While still unsettled, this instead returns the (cheap, small-N -- see the class
         * doc) full outline every time, matching {@link #appendPoints}'s own unsettled handling,
         * and marks the start cap as already covered by that result.
         *
         * <p>The very first settled call also splices in {@link #startCap} -- never itself part
         * of {@link #tailSince}'s result, since every later call assumes it was already drawn --
         * in the same trailing position {@link #assembleFullOutline} uses, so that call draws
         * exactly the shape a full-outline draw would for a stroke this short.
         *
         * <p>Used by {@code SharedGeometryInkOverlay}'s per-checkpoint local redraw of the
         * multi-buffered layer ({@code drawCheckpointDelta}); the exact, stroke-completion draw
         * still calls {@link #assembleFullOutline} directly.
         */
        List<Point> checkpointTail() {
            if (!settled) {
                checkpointLeftBoundary = 0;
                checkpointRightBoundary = 0;
                checkpointStartCapDrawn = true;
                return assembleFullOutline();
            }
            int leftBoundary = checkpointLeftBoundary;
            int rightBoundary = checkpointRightBoundary;
            checkpointLeftBoundary = leftPoints.size() - pendingLeftCount;
            checkpointRightBoundary = rightPoints.size() - pendingRightCount;
            List<Point> tail = tailSince(leftBoundary, rightBoundary);
            if (!checkpointStartCapDrawn) {
                checkpointStartCapDrawn = true;
                if (!startCap.isEmpty()) {
                    List<Point> withCap = new ArrayList<>(tail.size() + startCap.size());
                    withCap.addAll(tail);
                    withCap.addAll(startCap);
                    tail = withCap;
                }
            }
            return tail;
        }

        private List<Point> recomputeUnsettled() {
            boolean simulate = decideSimulatePressure(rawPoints);
            List<StrokePoint> strokePoints = getStrokePoints(
                    rawPoints, butterflyOptions(size, thinning, smoothing, streamline, simulate));
            replay(strokePoints, simulate);
            if (strokePoints.size() >= SETTLE_STROKE_POINTS) {
                settled = true;
                StrokePoint last = strokePoints.get(strokePoints.size() - 1);
                previousStrokePointPoint = last.point;
                previousStrokePointRunningLength = last.runningLength;
            }
            return assembleFullOutline();
        }

        /**
         * Resets this engine's rails/caps and replays its per-point step ({@link #processPoint},
         * via {@link #appendSettledStrokePoint}) over an already-known stroke-point sequence with
         * a known simulate-pressure decision. Used both for the warm-up preview above (a small
         * full recompute on every call, below {@link #SETTLE_STROKE_POINTS}) and by
         * {@link PerfectFreehandGeometry#getStrokeOutlineParts}'s batch case -- so neither
         * duplicates the streaming append path's per-point body.
         */
        private void replay(List<StrokePoint> strokePoints, boolean simulatePressure) {
            resetRails();
            simulatePressureDecision = simulatePressure;
            if (strokePoints.isEmpty()) return;
            initializeSettledState(strokePoints);
            for (StrokePoint sp : strokePoints) appendSettledStrokePoint(sp);
        }

        private void resetRails() {
            leftPoints.clear();
            rightPoints.clear();
            startCap.clear();
            endCap.clear();
            havePending = false;
            pendingLeftCount = 0;
            pendingRightCount = 0;
        }

        private void initializeSettledState(List<StrokePoint> strokePoints) {
            int pressureStartCount = Math.min(10, strokePoints.size() - 1);
            double seed = strokePoints.get(0).pressure();
            for (int i = 0; i < pressureStartCount; i++) {
                StrokePoint current = strokePoints.get(i);
                double pressure = simulatePressureDecision
                        ? current.simulatePressure(seed, size)
                        : current.pressure();
                seed = (seed + pressure) / 2.0;
            }
            previousPressure = seed;
            previousVector = strokePoints.get(0).vector;
            previousLeft = strokePoints.get(0).point;
            previousRight = previousLeft;
            isPreviousPointSharpCorner = false;
            pointIndex = 0;
            firstPointPoint = strokePoints.get(0).point;
        }

        /** Returns whether {@code raw} produced a new stroke point (false = no visible change). */
        private boolean appendOnePoint(Point raw) {
            StrokePoint sp = nextStrokePoint(raw);
            if (sp == null) return false;
            appendSettledStrokePoint(sp);
            return true;
        }

        private StrokePoint nextStrokePoint(Point raw) {
            Point withPressure = raw.withPressure(raw.pressure == null ? 0.5 : raw.pressure);
            Point candidate = previousStrokePointPoint.lerp(t, withPressure);
            if (candidate.equalsIncludingPressure(previousStrokePointPoint)) return null;
            double distance = candidate.distanceTo(previousStrokePointPoint);
            double runningLength = previousStrokePointRunningLength + distance;
            Point vector = candidate.unitVectorTo(previousStrokePointPoint);
            StrokePoint sp = new StrokePoint(candidate, vector, distance, runningLength);
            previousStrokePointPoint = candidate;
            previousStrokePointRunningLength = runningLength;
            return sp;
        }

        private void appendSettledStrokePoint(StrokePoint sp) {
            if (havePending) {
                truncate(leftPoints, pendingLeftCount);
                truncate(rightPoints, pendingRightCount);
                previousPressure = confirmedPressure;
                previousVector = confirmedVector;
                previousLeft = confirmedLeft;
                previousRight = confirmedRight;
                isPreviousPointSharpCorner = confirmedSharpCorner;
                pointIndex = confirmedIndex;
                boolean wasFirst = pointIndex == 0;
                processPoint(pendingPoint, sp.vector, false);
                if (wasFirst) computeStartCap();
            }
            confirmedPressure = previousPressure;
            confirmedVector = previousVector;
            confirmedLeft = previousLeft;
            confirmedRight = previousRight;
            confirmedSharpCorner = isPreviousPointSharpCorner;
            confirmedIndex = pointIndex;
            int leftBefore = leftPoints.size();
            int rightBefore = rightPoints.size();
            processPoint(sp, sp.vector, true);
            pendingLeftCount = leftPoints.size() - leftBefore;
            pendingRightCount = rightPoints.size() - rightBefore;
            pendingPoint = sp;
            havePending = true;
            recomputeEndCap(sp);
        }

        /**
         * The single implementation of perfect_freehand's per-point outline step, restricted to
         * the app's fixed options (isComplete=false, so no taper and no early-continue; both caps
         * enabled). {@code nextVector} is the successor's vector, or the point's own vector when
         * it has no successor yet (mirrors the last-point case of the upstream algorithm).
         */
        private void processPoint(StrokePoint strokePoint, Point nextVector, boolean isLast) {
            Point point = strokePoint.point;
            Point vector = strokePoint.vector;
            double radius;
            if (thinning != 0.0) {
                double pressure;
                if (simulatePressureDecision) {
                    pressure = strokePoint.simulatePressure(previousPressure, size);
                    previousPressure = pressure;
                } else {
                    pressure = strokePoint.pressure();
                }
                radius = getStrokeRadius(size, thinning, pressure);
            } else {
                radius = size / 2.0;
            }
            radius = Math.max(0.01, radius); // taper strength is always 1.0: no taper is enabled.
            lastRadius = radius;

            double nextDpr = vector.dot(nextVector);
            double previousDpr = vector.dot(previousVector);
            double maxDprForSharpCorner = size / 128.0;
            boolean isPointSharpCorner =
                    previousDpr < maxDprForSharpCorner && !isPreviousPointSharpCorner;
            boolean isNextPointSharpCorner = nextDpr < maxDprForSharpCorner;

            if (isPointSharpCorner || isNextPointSharpCorner) {
                Point previousOffset = previousVector.perpendicular().times(radius);
                for (double amount = 0.0; amount <= 1.0; amount += 1.0 / 13.0) {
                    leftPoints.add(point.minus(previousOffset).rotAround(point, PI * amount));
                    rightPoints.add(point.plus(previousOffset).rotAround(point, -PI * amount));
                }
                Point nextOffset = nextVector.perpendicular().times(radius);
                Point temporaryLeft = point.plus(nextOffset).rotAround(point, -PI);
                Point temporaryRight = point.minus(nextOffset).rotAround(point, PI);
                leftPoints.add(temporaryLeft);
                rightPoints.add(temporaryRight);
                previousLeft = temporaryRight;
                previousRight = temporaryLeft;
                if (isNextPointSharpCorner) isPreviousPointSharpCorner = true;
                pointIndex++;
                return;
            }

            isPreviousPointSharpCorner = false;
            if (isLast) {
                Point offset = vector.perpendicular().times(radius);
                leftPoints.add(point.minus(offset));
                rightPoints.add(point.plus(offset));
                pointIndex++;
                return;
            }

            Point offset = nextVector.lerp(nextDpr, vector).perpendicular().times(radius);
            Point temporaryLeft = point.minus(offset);
            if (pointIndex <= 1 || previousLeft.distanceSquaredTo(temporaryLeft) > minDistance) {
                leftPoints.add(temporaryLeft);
                previousLeft = temporaryLeft;
            }
            Point temporaryRight = point.plus(offset);
            if (pointIndex <= 1 || previousRight.distanceSquaredTo(temporaryRight) > minDistance) {
                rightPoints.add(temporaryRight);
                previousRight = temporaryRight;
            }
            previousVector = vector;
            pointIndex++;
        }

        private void computeStartCap() {
            // options.start.cap is always true for the app's fixed options (see
            // butterflyOptions), and taper is always disabled.
            List<Point> cap = new ArrayList<>();
            Point right0 = rightPoints.get(0);
            for (double amount = 1.0 / 13.0; amount <= 1.0; amount += 1.0 / 13.0) {
                cap.add(right0.rotAround(firstPointPoint, PI * amount));
            }
            startCap = cap;
        }

        private void recomputeEndCap(StrokePoint last) {
            // options.end.cap is always true for the app's fixed options; taper always disabled.
            Point direction = last.vector.negated().perpendicular();
            Point start = last.point.project(direction, lastRadius);
            List<Point> cap = new ArrayList<>();
            for (double amount = 1.0 / 29.0; amount <= 1.0; amount += 1.0 / 29.0) {
                cap.add(start.rotAround(last.point, PI * 3.0 * amount));
            }
            endCap = cap;
        }

        /**
         * The fragment that changed since {@code leftBoundary}/{@code rightBoundary} (the
         * confirmed rail lengths before this call), padded by {@link #OVERLAP} already-drawn
         * points on each side so the new fill shares an edge with -- rather than merely touches --
         * what is already on the front buffer, hiding any antialiasing seam between them.
         */
        private List<Point> tailSince(int leftBoundary, int rightBoundary) {
            List<Point> tail = new ArrayList<>();
            int leftFrom = Math.max(0, leftBoundary - OVERLAP);
            for (int i = leftFrom; i < leftPoints.size(); i++) tail.add(leftPoints.get(i));
            tail.addAll(endCap);
            int rightFrom = Math.max(0, rightBoundary - OVERLAP);
            for (int i = rightPoints.size() - 1; i >= rightFrom; i--) tail.add(rightPoints.get(i));
            return tail;
        }

        private static void truncate(List<Point> list, int count) {
            if (count > 0) list.subList(list.size() - count, list.size()).clear();
        }

        /** Mirrors PenRenderer.shouldSimulatePressure() in Butterfly's Dart source. */
        private static boolean decideSimulatePressure(List<Point> points) {
            if (points.size() < 2) return true;
            double reference = points.get(1).pressure;
            for (int i = 2; i < points.size(); i++) {
                if (points.get(i).pressure != reference) return false;
            }
            return true;
        }
    }

    /** Equivalent to perfect_freehand's {@code getStrokeRadius} under identity easing, the only
     * easing the app's fixed options ever use. */
    private static double getStrokeRadius(double size, double thinning, double pressure) {
        return size * (0.5 - thinning * (0.5 - pressure));
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
        final boolean simulatePressure;

        Options(
                double size,
                double thinning,
                double smoothing,
                double streamline,
                boolean simulatePressure) {
            this.size = size;
            this.thinning = thinning;
            this.smoothing = smoothing;
            this.streamline = streamline;
            this.simulatePressure = simulatePressure;
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
        final Point point;
        Point vector;
        final double distance;
        final double runningLength;

        StrokePoint(Point point, Point vector, double distance, double runningLength) {
            this.point = point;
            this.vector = vector;
            this.distance = distance;
            this.runningLength = runningLength;
        }

        double pressure() {
            return point.pressure == null ? 0.5 : point.pressure;
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

    /**
     * Axis-aligned bounding box in the same coordinate space as the {@link Point}s it was
     * computed from. Deliberately not {@link RectF}: this type exists specifically so
     * {@link #boundsOf} can be exercised from a plain JVM unit test without an Android runtime,
     * the same reason {@link PathOperation} exists alongside {@link Path}.
     */
    static final class Bounds {
        final double left, top, right, bottom;

        Bounds(double left, double top, double right, double bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        Bounds union(Bounds other) {
            return new Bounds(
                    Math.min(left, other.left), Math.min(top, other.top),
                    Math.max(right, other.right), Math.max(bottom, other.bottom));
        }

        /** Grown by {@code amount} on every side (a negative amount shrinks it). */
        Bounds padded(double amount) {
            return new Bounds(left - amount, top - amount, right + amount, bottom + amount);
        }
    }

    /**
     * The axis-aligned bounding box of every point in {@code points}, or {@code null} for an
     * empty list. Pure and stateless -- used to size the local redraw rectangle for a
     * checkpoint's multi-buffered-layer update ({@code SharedGeometryInkOverlay.drawCheckpointDelta})
     * instead of reassembling and redrawing the whole stroke every checkpoint.
     */
    static Bounds boundsOf(List<Point> points) {
        if (points.isEmpty()) {
            return null;
        }
        double left = Double.POSITIVE_INFINITY;
        double top = Double.POSITIVE_INFINITY;
        double right = Double.NEGATIVE_INFINITY;
        double bottom = Double.NEGATIVE_INFINITY;
        for (Point point : points) {
            if (point.x < left) left = point.x;
            if (point.x > right) right = point.x;
            if (point.y < top) top = point.y;
            if (point.y > bottom) bottom = point.y;
        }
        return new Bounds(left, top, right, bottom);
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
