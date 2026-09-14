package dev.linwood.butterfly;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Verifies PerfectFreehandGeometry.java's outline and path-conversion output against fixtures
 * generated straight from the real Dart {@code package:perfect_freehand} implementation the app
 * depends on (see {@code app/test/tool/generate_perfect_freehand_fixtures.dart}). The Java port
 * has no upstream test suite of its own to lean on, so this is what stands between a subtle
 * divergence and every stylus stroke drawn in shared_geometry mode.
 */
public class PerfectFreehandGeometryParityTest {
    private static final double TOLERANCE = 1e-3;
    private static final String FIXTURE_RESOURCE = "perfect_freehand_fixtures.json";

    @Test
    public void outlineAndPathMatchDartForEveryFixture() throws IOException {
        List<Object> fixtures = castList(readFixtureRoot().get("fixtures"));
        assertTrue("expected at least the four documented fixtures", fixtures.size() >= 4);
        for (Object rawFixture : fixtures) {
            checkFixture(castMap(rawFixture));
        }
    }

    private void checkFixture(Map<String, Object> fixture) {
        String name = (String) fixture.get("name");
        Map<String, Object> options = castMap(fixture.get("options"));
        List<Object> rawPoints = castList(fixture.get("points"));
        List<Object> expectedOutline = castList(fixture.get("outline"));
        List<Object> expectedPath = castList(fixture.get("path"));

        List<PerfectFreehandGeometry.Point> points = new ArrayList<>(rawPoints.size());
        for (Object rawPoint : rawPoints) {
            List<Object> triple = castList(rawPoint);
            points.add(new PerfectFreehandGeometry.Point(
                    number(triple.get(0)), number(triple.get(1)), number(triple.get(2))));
        }

        // The exact same 5-argument call SharedGeometryInkOverlay.drawSnapshot makes: this test
        // exercises production's real call boundary, not a hypothetical one.
        PerfectFreehandGeometry.Options options5 = PerfectFreehandGeometry.butterflyOptions(
                number(options.get("size")), number(options.get("thinning")),
                number(options.get("smoothing")), number(options.get("streamline")),
                (Boolean) options.get("simulatePressure"));

        List<PerfectFreehandGeometry.Point> outline =
                PerfectFreehandGeometry.getStroke(points, options5);

        assertEquals(name + ": outline point count", expectedOutline.size(), outline.size());
        double maxOutlineError = 0;
        for (int i = 0; i < outline.size(); i++) {
            List<Object> expected = castList(expectedOutline.get(i));
            PerfectFreehandGeometry.Point actual = outline.get(i);
            maxOutlineError = Math.max(maxOutlineError,
                    Math.abs(actual.x - number(expected.get(0))));
            maxOutlineError = Math.max(maxOutlineError,
                    Math.abs(actual.y - number(expected.get(1))));
        }
        assertTrue(name + ": outline max abs error " + maxOutlineError + " exceeds " + TOLERANCE,
                maxOutlineError <= TOLERANCE);

        // Also verify the path-building translation (mirrors buildFilledQuadraticPath) using the
        // pure-JVM PathOperation representation, since android.graphics.Path is unusable here.
        List<PerfectFreehandGeometry.PathOperation> path =
                PerfectFreehandGeometry.filledQuadraticPathOperations(outline);
        assertEquals(name + ": path operation count", expectedPath.size(), path.size());
        double maxPathError = 0;
        for (int i = 0; i < path.size(); i++) {
            maxPathError = Math.max(maxPathError,
                    comparePathOperation(castList(expectedPath.get(i)), path.get(i)));
        }
        assertTrue(name + ": path max abs error " + maxPathError + " exceeds " + TOLERANCE,
                maxPathError <= TOLERANCE);

        System.out.println(name + ": outline maxAbsError=" + maxOutlineError
                + " path maxAbsError=" + maxPathError
                + " (points=" + points.size() + ", outline=" + outline.size() + ")");
    }

    private static double comparePathOperation(List<Object> expected,
            PerfectFreehandGeometry.PathOperation actual) {
        String tag = (String) expected.get(0);
        PerfectFreehandGeometry.PathOperation.Kind expectedKind = switch (tag) {
            case "M" -> PerfectFreehandGeometry.PathOperation.Kind.MOVE;
            case "Q" -> PerfectFreehandGeometry.PathOperation.Kind.QUADRATIC;
            case "O" -> PerfectFreehandGeometry.PathOperation.Kind.OVAL;
            default -> throw new IllegalArgumentException("Unknown path op tag: " + tag);
        };
        assertEquals("path op kind", expectedKind, actual.kind);
        double maxError = 0;
        for (int v = 0; v < actual.values.length; v++) {
            maxError = Math.max(maxError, Math.abs(actual.values[v] - number(expected.get(v + 1))));
        }
        return maxError;
    }

    private static Map<String, Object> readFixtureRoot() throws IOException {
        try (InputStream stream = PerfectFreehandGeometryParityTest.class
                .getResourceAsStream(FIXTURE_RESOURCE)) {
            if (stream == null) {
                throw new IOException("Missing test resource: " + FIXTURE_RESOURCE);
            }
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return castMap(MiniJson.parse(text));
        }
    }

    private static double number(Object value) {
        return ((Number) value).doubleValue();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(Object value) {
        return (List<Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }
}
