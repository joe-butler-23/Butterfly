package dev.linwood.butterfly;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class StylusLatencyCoreTest {
    @Test
    public void parsesOnlyTheFourLabModes() {
        assertEquals(StylusLatencyMode.Value.FLUTTER_ONLY, StylusLatencyMode.parse(null));
        assertEquals(StylusLatencyMode.Value.FLUTTER_ONLY, StylusLatencyMode.parse("flutter_only"));
        assertEquals(StylusLatencyMode.Value.INK_PREDICTION_OFF,
                StylusLatencyMode.parse("ink_prediction_off"));
        assertEquals(StylusLatencyMode.Value.INK_PREDICTION_ON,
                StylusLatencyMode.parse("ink_prediction_on"));
        assertEquals(StylusLatencyMode.Value.SHARED_GEOMETRY,
                StylusLatencyMode.parse("shared_geometry"));
        assertEquals(StylusLatencyMode.Value.FLUTTER_ONLY, StylusLatencyMode.parse("unknown"));
    }

    @Test
    public void canceledNativeStrokeDoesNotQuarantineFollowingStroke() {
        InkHandoffCoordinator<String> handoff = new InkHandoffCoordinator<>();
        handoff.setGeneration(7);
        handoff.addNativeStroke(7, 10_000, "canceled");
        handoff.cancelNative("canceled");
        handoff.register(new InkHandoffCoordinator.Registration(7, 1, 10_000));
        assertNull(handoff.cancel(7, 1, 10_000));
        handoff.addNativeStroke(7, 20_000, "next");
        handoff.register(new InkHandoffCoordinator.Registration(7, 2, 20_000));
        handoff.cancelNative("next");
        assertNull(handoff.acknowledge(7, 2, 20_000, "element", 2));

        handoff.rejectNativeStroke(7, 30_000);
        handoff.register(new InkHandoffCoordinator.Registration(7, 3, 30_000));
        assertNull(handoff.acknowledge(7, 3, 30_000, "declined", 2));

        handoff.addNativeStroke(7, 40_000, "following");
        handoff.register(new InkHandoffCoordinator.Registration(7, 4, 40_000));
        assertNull(handoff.markNativeFinished("following"));
        assertEquals("following", handoff.acknowledge(7, 4, 40_000, "element", 2));
    }

    @Test
    public void retiresWetInkOnlyAfterNativeFinishAndExactFlutterPaintAck() {
        InkHandoffCoordinator<String> handoff = new InkHandoffCoordinator<>();
        handoff.setGeneration(7);
        handoff.addNativeStroke(7, 10_000, "native");
        handoff.register(new InkHandoffCoordinator.Registration(7, 1, 10_000));
        assertNull(handoff.acknowledge(7, 1, 10_000, "element", 3));
        assertEquals("native", handoff.markNativeFinished("native"));

        handoff.addNativeStroke(7, 20_000, "next");
        handoff.register(new InkHandoffCoordinator.Registration(7, 2, 20_000));
        assertNull(handoff.markNativeFinished("next"));
        assertEquals("next", handoff.acknowledge(7, 2, 20_000, "element-2", 4));
    }
}
