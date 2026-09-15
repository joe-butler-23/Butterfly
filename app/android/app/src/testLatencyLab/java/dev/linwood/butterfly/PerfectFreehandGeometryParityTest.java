package dev.linwood.butterfly;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Scanner;
import java.util.Set;

/**
 * Verifies PerfectFreehandGeometry.java's outline and path-conversion output against fixtures
 * generated straight from the real Dart {@code package:perfect_freehand} implementation the app
 * depends on (see {@code app/test/tool/generate_perfect_freehand_fixtures.dart}). The Java port
 * has no upstream test suite of its own to lean on, so this is what stands between a subtle
 * divergence and every stylus stroke drawn in shared_geometry mode.
 *
 * <p>It also proves that {@link PerfectFreehandGeometry.IncrementalOutline} -- the append-only
 * engine {@code SharedGeometryInkOverlay} drives frame by frame -- produces exactly the same
 * outline as a from-scratch computation, so drawing only its per-frame tail is safe.
 */
public class PerfectFreehandGeometryParityTest {
    private static final double TOLERANCE = 1e-3;
    // Tighter than TOLERANCE above: the incremental engine uses the exact same formulas as the
    // from-scratch computation (not an independent, cross-language reimplementation), so its
    // output should match to floating-point noise, not merely to visual tolerance.
    private static final double INCREMENTAL_TOLERANCE = 1e-6;
    private static final String FIXTURE_RESOURCE = "perfect_freehand_fixtures.txt";
    // Large enough to comfortably cross IncrementalOutline's settle threshold in one call, so
    // the test exercises the "batch spans the settle boundary" path too (see appendPoints).
    private static final int INITIAL_BATCH = 20;

    private static final class Fixture {
        final String name;
        final double size, thinning, smoothing, streamline;
        final boolean simulatePressure;
        final List<PerfectFreehandGeometry.Point> points;
        final List<double[]> outline;
        final List<PerfectFreehandGeometry.PathOperation> path;

        Fixture(String name, double size, double thinning, double smoothing, double streamline,
                boolean simulatePressure, List<PerfectFreehandGeometry.Point> points,
                List<double[]> outline, List<PerfectFreehandGeometry.PathOperation> path) {
            this.name = name;
            this.size = size;
            this.thinning = thinning;
            this.smoothing = smoothing;
            this.streamline = streamline;
            this.simulatePressure = simulatePressure;
            this.points = points;
            this.outline = outline;
            this.path = path;
        }

        PerfectFreehandGeometry.Options options() {
            return PerfectFreehandGeometry.butterflyOptions(
                    size, thinning, smoothing, streamline, simulatePressure);
        }
    }

    @Test
    public void outlineAndPathMatchDartForEveryFixture() throws IOException {
        List<Fixture> fixtures = readFixtures();
        assertTrue("expected at least the four documented fixtures", fixtures.size() >= 4);
        for (Fixture fixture : fixtures) {
            checkFixture(fixture);
        }
    }

    @Test
    public void incrementalOutlineMatchesFullComputationForLongFixture() throws IOException {
        Fixture fixture = findFixture(readFixtures(), "long_400pt");
        List<PerfectFreehandGeometry.Point> points = fixture.points;
        assertTrue("fixture too short to exercise settling", points.size() > INITIAL_BATCH + 10);

        PerfectFreehandGeometry.Options options = fixture.options();
        PerfectFreehandGeometry.OutlineParts reference = PerfectFreehandGeometry.getStrokeOutlineParts(
                PerfectFreehandGeometry.getStrokePoints(points, options), options);

        // Drive the engine exactly the way SharedGeometryInkOverlay does: an initial batch (the
        // points already appended by the time the first frame renders), then one point per call
        // afterward -- and record the union of every drawn tail along the way.
        PerfectFreehandGeometry.IncrementalOutline engine = new PerfectFreehandGeometry.IncrementalOutline(
                fixture.size, fixture.thinning, fixture.smoothing, fixture.streamline);
        Set<String> tailUnion = new LinkedHashSet<>();
        addTail(tailUnion, engine.appendPoints(points.subList(0, INITIAL_BATCH)).polygon);
        for (int i = INITIAL_BATCH; i < points.size(); i++) {
            addTail(tailUnion, engine.appendPoints(List.of(points.get(i))).polygon);
        }

        // 1) The engine's own final rails equal a from-scratch computation on the same points:
        // the core proof that the incremental path is exact, not just visually close.
        assertPointListsEqual("left rail", reference.left, engine.leftPointsSnapshot());
        assertPointListsEqual("right rail", reference.right, engine.rightPointsSnapshot());

        // 2) Every point of the full outline was actually drawn by the union of tails.
        for (PerfectFreehandGeometry.Point expected : reference.assemble()) {
            String key = key(expected);
            assertTrue("outline point " + key + " was never drawn by any incremental tail",
                    tailUnion.contains(key));
        }
    }

    @Test
    public void checkpointTailUnionMatchesFullOutlineForLongFixture() throws IOException {
        Fixture fixture = findFixture(readFixtures(), "long_400pt");
        List<PerfectFreehandGeometry.Point> points = fixture.points;
        assertTrue("fixture too short to exercise multiple checkpoints", points.size() > 50);

        PerfectFreehandGeometry.Options options = fixture.options();
        PerfectFreehandGeometry.OutlineParts reference = PerfectFreehandGeometry.getStrokeOutlineParts(
                PerfectFreehandGeometry.getStrokePoints(points, options), options);

        // Drive the engine the way SharedGeometryInkOverlay does on the front-buffer path
        // (appendPoints for every new point) and, on an interval that deliberately does not
        // evenly divide the fixture or align with INITIAL_BATCH, also pull a checkpointTail() the
        // way drawCheckpointDelta does for the multi-buffered layer -- proving checkpointTail()'s
        // incremental result is correct regardless of exactly which points count as a
        // "checkpoint", not just the app's own fixed 16-point cadence.
        final int checkpointInterval = 13;
        PerfectFreehandGeometry.IncrementalOutline engine = new PerfectFreehandGeometry.IncrementalOutline(
                fixture.size, fixture.thinning, fixture.smoothing, fixture.streamline);
        Set<String> checkpointUnion = new LinkedHashSet<>();
        engine.appendPoints(points.subList(0, INITIAL_BATCH));
        for (int i = INITIAL_BATCH; i < points.size(); i++) {
            engine.appendPoints(List.of(points.get(i)));
            if ((i + 1) % checkpointInterval == 0) {
                addTail(checkpointUnion, engine.checkpointTail());
            }
        }
        // Stroke completion also checkpoints (a final, possibly-partial interval): mirror that so
        // the union covers the whole stroke, not just whole checkpointInterval-point prefixes.
        addTail(checkpointUnion, engine.checkpointTail());

        for (PerfectFreehandGeometry.Point expected : reference.assemble()) {
            String key = key(expected);
            assertTrue("outline point " + key + " was never drawn by any checkpoint delta",
                    checkpointUnion.contains(key));
        }
    }

    @Test
    public void boundsOfComputesTheTightBoundingBoxOfGivenPoints() {
        List<PerfectFreehandGeometry.Point> points = List.of(
                new PerfectFreehandGeometry.Point(3.0, -2.0, null),
                new PerfectFreehandGeometry.Point(-1.5, 7.0, null),
                new PerfectFreehandGeometry.Point(10.0, 0.0, null));
        PerfectFreehandGeometry.Bounds bounds = PerfectFreehandGeometry.boundsOf(points);
        assertEquals(-1.5, bounds.left, 0.0);
        assertEquals(-2.0, bounds.top, 0.0);
        assertEquals(10.0, bounds.right, 0.0);
        assertEquals(7.0, bounds.bottom, 0.0);
    }

    @Test
    public void boundsOfReturnsNullForAnEmptyList() {
        assertNull(PerfectFreehandGeometry.boundsOf(List.of()));
    }

    @Test
    public void boundsUnionCoversBothInputs() {
        PerfectFreehandGeometry.Bounds a = PerfectFreehandGeometry.boundsOf(List.of(
                new PerfectFreehandGeometry.Point(0.0, 0.0, null),
                new PerfectFreehandGeometry.Point(2.0, 2.0, null)));
        PerfectFreehandGeometry.Bounds b = PerfectFreehandGeometry.boundsOf(List.of(
                new PerfectFreehandGeometry.Point(5.0, -3.0, null),
                new PerfectFreehandGeometry.Point(6.0, 1.0, null)));
        PerfectFreehandGeometry.Bounds union = a.union(b);
        assertEquals(0.0, union.left, 0.0);
        assertEquals(-3.0, union.top, 0.0);
        assertEquals(6.0, union.right, 0.0);
        assertEquals(2.0, union.bottom, 0.0);
    }

    @Test
    public void boundsPaddedGrowsEverySideByTheSameAmount() {
        PerfectFreehandGeometry.Bounds bounds = new PerfectFreehandGeometry.Bounds(1.0, 2.0, 3.0, 4.0);
        PerfectFreehandGeometry.Bounds padded = bounds.padded(1.5);
        assertEquals(-0.5, padded.left, 1e-9);
        assertEquals(0.5, padded.top, 1e-9);
        assertEquals(4.5, padded.right, 1e-9);
        assertEquals(5.5, padded.bottom, 1e-9);
    }

    private static void addTail(Set<String> union, List<PerfectFreehandGeometry.Point> tail) {
        for (PerfectFreehandGeometry.Point point : tail) {
            union.add(key(point));
        }
    }

    private static String key(PerfectFreehandGeometry.Point point) {
        return Math.round(point.x / INCREMENTAL_TOLERANCE) + ":"
                + Math.round(point.y / INCREMENTAL_TOLERANCE);
    }

    private static void assertPointListsEqual(String label,
            List<PerfectFreehandGeometry.Point> expected,
            List<PerfectFreehandGeometry.Point> actual) {
        assertEquals(label + ": point count", expected.size(), actual.size());
        double maxError = 0;
        for (int i = 0; i < expected.size(); i++) {
            maxError = Math.max(maxError, Math.abs(expected.get(i).x - actual.get(i).x));
            maxError = Math.max(maxError, Math.abs(expected.get(i).y - actual.get(i).y));
        }
        assertTrue(label + ": max abs error " + maxError + " exceeds " + INCREMENTAL_TOLERANCE,
                maxError <= INCREMENTAL_TOLERANCE);
    }

    private static Fixture findFixture(List<Fixture> fixtures, String name) {
        for (Fixture fixture : fixtures) {
            if (fixture.name.equals(name)) return fixture;
        }
        fail("missing fixture: " + name);
        throw new AssertionError("unreachable");
    }

    private void checkFixture(Fixture fixture) {
        PerfectFreehandGeometry.Options options = fixture.options();

        // The exact same 5-argument call SharedGeometryInkOverlay.drawFullOutline exercises via
        // IncrementalOutline's fixed options: this test exercises production's real call
        // boundary, not a hypothetical one.
        List<PerfectFreehandGeometry.Point> outline =
                PerfectFreehandGeometry.getStroke(fixture.points, options);

        assertEquals(fixture.name + ": outline point count", fixture.outline.size(), outline.size());
        double maxOutlineError = 0;
        for (int i = 0; i < outline.size(); i++) {
            double[] expected = fixture.outline.get(i);
            PerfectFreehandGeometry.Point actual = outline.get(i);
            maxOutlineError = Math.max(maxOutlineError, Math.abs(actual.x - expected[0]));
            maxOutlineError = Math.max(maxOutlineError, Math.abs(actual.y - expected[1]));
        }
        assertTrue(fixture.name + ": outline max abs error " + maxOutlineError + " exceeds "
                + TOLERANCE, maxOutlineError <= TOLERANCE);

        // Also verify the path-building translation (mirrors buildFilledQuadraticPath) using the
        // pure-JVM PathOperation representation, since android.graphics.Path is unusable here.
        List<PerfectFreehandGeometry.PathOperation> path =
                PerfectFreehandGeometry.filledQuadraticPathOperations(outline);
        assertEquals(fixture.name + ": path operation count", fixture.path.size(), path.size());
        double maxPathError = 0;
        for (int i = 0; i < path.size(); i++) {
            maxPathError = Math.max(maxPathError, comparePathOperation(fixture.path.get(i), path.get(i)));
        }
        assertTrue(fixture.name + ": path max abs error " + maxPathError + " exceeds " + TOLERANCE,
                maxPathError <= TOLERANCE);

        System.out.println(fixture.name + ": outline maxAbsError=" + maxOutlineError
                + " path maxAbsError=" + maxPathError
                + " (points=" + fixture.points.size() + ", outline=" + outline.size() + ")");
    }

    private static double comparePathOperation(PerfectFreehandGeometry.PathOperation expected,
            PerfectFreehandGeometry.PathOperation actual) {
        assertEquals("path op kind", expected.kind, actual.kind);
        double maxError = 0;
        for (int v = 0; v < actual.values.length; v++) {
            maxError = Math.max(maxError, Math.abs(actual.values[v] - expected.values[v]));
        }
        return maxError;
    }

    private static List<Fixture> readFixtures() throws IOException {
        try (InputStream stream = PerfectFreehandGeometryParityTest.class
                .getResourceAsStream(FIXTURE_RESOURCE)) {
            if (stream == null) {
                throw new IOException("Missing test resource: " + FIXTURE_RESOURCE);
            }
            Scanner scanner = new Scanner(stream, StandardCharsets.UTF_8);
            scanner.useLocale(Locale.ROOT);
            try {
                int fixtureCount = scanner.nextInt();
                List<Fixture> fixtures = new ArrayList<>(fixtureCount);
                for (int f = 0; f < fixtureCount; f++) {
                    fixtures.add(readFixture(scanner));
                }
                return fixtures;
            } catch (NoSuchElementException | IllegalStateException error) {
                throw new IOException("Malformed fixture resource: " + FIXTURE_RESOURCE, error);
            }
        }
    }

    private static Fixture readFixture(Scanner scanner) {
        String name = scanner.next();
        double size = scanner.nextDouble();
        double thinning = scanner.nextDouble();
        double smoothing = scanner.nextDouble();
        double streamline = scanner.nextDouble();
        boolean simulatePressure = scanner.nextInt() != 0;

        int pointCount = scanner.nextInt();
        List<PerfectFreehandGeometry.Point> points = new ArrayList<>(pointCount);
        for (int i = 0; i < pointCount; i++) {
            points.add(new PerfectFreehandGeometry.Point(
                    scanner.nextDouble(), scanner.nextDouble(), scanner.nextDouble()));
        }

        int outlineCount = scanner.nextInt();
        List<double[]> outline = new ArrayList<>(outlineCount);
        for (int i = 0; i < outlineCount; i++) {
            outline.add(new double[] {scanner.nextDouble(), scanner.nextDouble()});
        }

        int pathCount = scanner.nextInt();
        List<PerfectFreehandGeometry.PathOperation> path = new ArrayList<>(pathCount);
        for (int i = 0; i < pathCount; i++) {
            String tag = scanner.next();
            path.add(switch (tag) {
                case "M" -> PerfectFreehandGeometry.PathOperation.move(
                        scanner.nextDouble(), scanner.nextDouble());
                case "Q" -> PerfectFreehandGeometry.PathOperation.quadratic(
                        scanner.nextDouble(), scanner.nextDouble(),
                        scanner.nextDouble(), scanner.nextDouble());
                case "O" -> PerfectFreehandGeometry.PathOperation.oval(
                        scanner.nextDouble(), scanner.nextDouble(), scanner.nextDouble());
                default -> throw new IllegalStateException("Unknown path op tag: " + tag);
            });
        }
        return new Fixture(name, size, thinning, smoothing, streamline, simulatePressure, points,
                outline, path);
    }
}
