package dev.linwood.butterfly;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Covers the two pure calculations behind the 20ms prediction horizon and its lighter predicted
 * tail: {@link SharedGeometryInkOverlay#predictionTimeNanos} (the absolute target time handed to
 * {@code android.view.MotionPredictor#predict(long)}) and
 * {@link SharedGeometryInkOverlay#predictedTailColor} (the ~45%-alpha colour the predicted tail
 * is drawn with). Both are static and package-visible specifically so they can be exercised here
 * without an Android runtime.
 */
public class SharedGeometryInkOverlayPredictionTest {
    @Test
    public void predictionTimeAddsHorizonToTheLatestEventTimeNotWallClockNow() {
        // 1_000ms event + 20ms horizon = 1_020ms, expressed in nanoseconds.
        assertEquals(1_020_000_000L,
                SharedGeometryInkOverlay.predictionTimeNanos(1_000L, 20));
    }

    @Test
    public void predictionTimeWithZeroHorizonIsJustTheEventTime() {
        assertEquals(1_000_000_000L,
                SharedGeometryInkOverlay.predictionTimeNanos(1_000L, 0));
    }

    @Test
    public void predictionTimeTracksTheEventNotAFixedOrigin() {
        // Two different event timestamps with the same horizon must not collapse to the same
        // target time -- that would mean the horizon is being measured from some fixed/default
        // origin (e.g. process start, or a stale "now") instead of the event actually recorded.
        long first = SharedGeometryInkOverlay.predictionTimeNanos(5_000L, 20);
        long second = SharedGeometryInkOverlay.predictionTimeNanos(5_016L, 20);
        assertEquals(16_000_000L, second - first);
    }

    @Test
    public void predictedTailColorIsApproximately45PercentAlpha() {
        int fullyOpaqueRed = 0xFFFF0000;
        int predicted = SharedGeometryInkOverlay.predictedTailColor(fullyOpaqueRed);
        int alpha = (predicted >>> 24) & 0xFF;
        // Math.round(255 * 0.45) == 115.
        assertEquals(115, alpha);
        // RGB channels are untouched.
        assertEquals(0x00FF0000, predicted & 0x00FFFFFF);
    }

    @Test
    public void predictedTailColorPreservesArbitraryRgbAndDropsIncomingAlpha() {
        // parsePolicy() only ever hands this fully-opaque colours, but the helper itself must
        // not depend on that -- it always substitutes its own alpha byte.
        int input = 0x12345678;
        int predicted = SharedGeometryInkOverlay.predictedTailColor(input);
        assertEquals(0x00345678, predicted & 0x00FFFFFF);
        assertEquals(115, (predicted >>> 24) & 0xFF);
    }
}
