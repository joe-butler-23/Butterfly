package dev.linwood.butterfly;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StylusLatencyCoreTest {
    @Test
    public void parsesOnlyTheTwoLabModes() {
        assertEquals(StylusLatencyMode.Value.SHARED_GEOMETRY, StylusLatencyMode.parse(null));
        assertEquals(StylusLatencyMode.Value.FLUTTER_ONLY, StylusLatencyMode.parse("flutter_only"));
        assertEquals(StylusLatencyMode.Value.SHARED_GEOMETRY,
                StylusLatencyMode.parse("shared_geometry"));
        assertEquals(StylusLatencyMode.Value.SHARED_GEOMETRY, StylusLatencyMode.parse("unknown"));
    }

    @Test
    public void finishedThenAckedRetires() {
        InkHandoffCoordinator<String> handoff = new InkHandoffCoordinator<>();
        handoff.setGeneration(7);
        handoff.addNativeStroke(7, 10_000, "stroke");
        handoff.register(7, 10_000);
        assertNull(handoff.markNativeFinished("stroke"));
        assertEquals("stroke", handoff.acknowledge(7, 10_000, "element", 3));
        // Retirable, but still tracked until the deferred frame callback confirms it.
        assertEquals(1, handoff.retainedCount());
        assertEquals("stroke", handoff.confirmRetired("stroke"));
        assertEquals(0, handoff.retainedCount());
    }

    @Test
    public void ackedThenFinishedRetires() {
        InkHandoffCoordinator<String> handoff = new InkHandoffCoordinator<>();
        handoff.setGeneration(7);
        handoff.addNativeStroke(7, 10_000, "stroke");
        handoff.register(7, 10_000);
        assertNull(handoff.acknowledge(7, 10_000, "element", 3));
        assertEquals("stroke", handoff.markNativeFinished("stroke"));
        assertEquals(1, handoff.retainedCount());
        assertEquals("stroke", handoff.confirmRetired("stroke"));
        assertEquals(0, handoff.retainedCount());
    }

    @Test
    public void cancelClearsAndLeavesNoTraceForNativeFinish() {
        InkHandoffCoordinator<String> handoff = new InkHandoffCoordinator<>();
        handoff.setGeneration(7);
        handoff.addNativeStroke(7, 10_000, "stroke");
        handoff.register(7, 10_000);
        assertEquals("stroke", handoff.cancel(7, 10_000));
        assertNull(handoff.markNativeFinished("stroke"));
    }

    @Test
    public void unknownAckAndCancelAreIgnored() {
        InkHandoffCoordinator<String> handoff = new InkHandoffCoordinator<>();
        handoff.setGeneration(7);
        assertNull(handoff.acknowledge(7, 99_000, "element", 3));
        assertNull(handoff.cancel(7, 99_000));
    }

    @Test
    public void registrationAfterNativeAbortIsIgnored() {
        InkHandoffCoordinator<String> handoff = new InkHandoffCoordinator<>();
        handoff.setGeneration(7);
        handoff.addNativeStroke(7, 10_000, "stroke");
        handoff.cancelNative("stroke");
        assertFalse(handoff.register(7, 10_000));
        assertNull(handoff.acknowledge(7, 10_000, "element", 3));
    }

    @Test
    public void registrationAcceptsOnlyTheExactLiveNativeStroke() {
        InkHandoffCoordinator<String> handoff = new InkHandoffCoordinator<>();
        handoff.setGeneration(7);
        assertFalse(handoff.register(7, 10_000));
        handoff.addNativeStroke(7, 10_000, "stroke");
        assertFalse(handoff.register(8, 10_000));
        assertFalse(handoff.register(7, 11_000));
        assertTrue(handoff.register(7, 10_000));
        handoff.cancelNative("stroke");
        assertFalse(handoff.register(7, 10_000));
    }

    @Test
    public void confirmRetiredIsANoOpIfAlreadyRemoved() {
        InkHandoffCoordinator<String> handoff = new InkHandoffCoordinator<>();
        handoff.setGeneration(7);
        handoff.addNativeStroke(7, 10_000, "stroke");
        handoff.register(7, 10_000);
        assertNull(handoff.markNativeFinished("stroke"));
        assertEquals("stroke", handoff.acknowledge(7, 10_000, "element", 3));
        // A cancel races ahead of the deferred frame callback and removes it first.
        assertEquals("stroke", handoff.cancel(7, 10_000));
        assertNull(handoff.confirmRetired("stroke"));
    }

    @Test
    public void overflowEvictsOldestWithClear() {
        InkHandoffCoordinator<String> handoff = new InkHandoffCoordinator<>();
        handoff.setGeneration(7);
        for (int sequence = 1; sequence <= 8; sequence++) {
            assertNull(handoff.addNativeStroke(7, sequence * 10_000L, "stroke-" + sequence));
        }
        assertEquals(8, handoff.retainedCount());
        assertEquals("stroke-1", handoff.addNativeStroke(7, 90_000, "stroke-9"));
        assertEquals(8, handoff.retainedCount());
    }
}
