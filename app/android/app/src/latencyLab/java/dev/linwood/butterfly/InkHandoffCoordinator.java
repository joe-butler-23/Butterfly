package dev.linwood.butterfly;

import android.view.Choreographer;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded handoff between a native wet-ink stroke and Flutter's dry-ink acknowledgement.
 *
 * <p>Dart's {@code sourceTimestampUs} is derived from {@code MotionEvent.getEventTime()*1000}
 * by the Flutter Android embedder, so it is bit-for-bit identical to the native DOWN event's
 * timestamp. That lets every message be matched by an exact key instead of nearest-timestamp
 * fuzzy matching, which is what makes this bounded map sufficient on its own: an unknown key
 * simply means there is no wet ink to reconcile.
 */
final class InkHandoffCoordinator<T> {
    private static final int MAX_RETAINED = 8;

    private static final class Entry<T> {
        final T token;
        final long sourceTimestampUs;
        boolean registered;
        boolean nativeFinished;
        boolean acknowledged;

        Entry(T token, long sourceTimestampUs) {
            this.token = token;
            this.sourceTimestampUs = sourceTimestampUs;
        }
    }

    private final Map<Long, Entry<T>> byTimestamp = new LinkedHashMap<>();
    private final Map<T, Entry<T>> byToken = new HashMap<>();
    private long generation = Long.MIN_VALUE;

    void setGeneration(long value) {
        clear();
        generation = value;
    }

    void clear() {
        byTimestamp.clear();
        byToken.clear();
    }

    int retainedCount() { return byTimestamp.size(); }

    /**
     * Starts tracking a native stroke. Returns the oldest entry's token if bounding the map
     * evicted it, in which case the caller must clear that token's wet ink immediately.
     */
    @Nullable T addNativeStroke(long expectedGeneration, long sourceTimestampUs, T token) {
        if (expectedGeneration != generation || token == null || sourceTimestampUs < 0) {
            return null;
        }
        Entry<T> entry = new Entry<>(token, sourceTimestampUs);
        byTimestamp.put(sourceTimestampUs, entry);
        byToken.put(token, entry);
        if (byTimestamp.size() <= MAX_RETAINED) return null;
        Iterator<Entry<T>> oldest = byTimestamp.values().iterator();
        Entry<T> evicted = oldest.next();
        oldest.remove();
        byToken.remove(evicted.token);
        return evicted.token;
    }

    /** A registration for an unknown key (native never started, or already aborted) is a no-op. */
    void register(long expectedGeneration, long sourceTimestampUs) {
        if (expectedGeneration != generation || sourceTimestampUs < 0) return;
        Entry<T> entry = byTimestamp.get(sourceTimestampUs);
        if (entry != null) entry.registered = true;
    }

    /**
     * Returns the token once native has already finished it too, meaning the stroke is fully
     * retirable. The entry stays tracked (so {@link #retainedCount} still counts it, keeping a
     * new stroke from starting mid-handoff) until the caller confirms the retirement from a
     * frame callback (see {@link #deferClear} and {@link #confirmRetired}); an unknown key
     * means there is no wet ink to reconcile.
     */
    @Nullable T acknowledge(long expectedGeneration, long sourceTimestampUs, String elementId,
            int pointCount) {
        if (expectedGeneration != generation || sourceTimestampUs < 0 || elementId == null
                || elementId.isEmpty() || pointCount <= 1) return null;
        Entry<T> entry = byTimestamp.get(sourceTimestampUs);
        if (entry == null) return null;
        entry.acknowledged = true;
        return entry.nativeFinished ? entry.token : null;
    }

    /** Cancellation removes the entry immediately: no dry ink exists yet to race against. */
    @Nullable T cancel(long expectedGeneration, long sourceTimestampUs) {
        if (expectedGeneration != generation || sourceTimestampUs < 0) return null;
        Entry<T> entry = byTimestamp.get(sourceTimestampUs);
        return entry == null ? null : retire(entry);
    }

    /** Native-side abort of an in-progress stroke; also immediate, for the same reason. */
    @Nullable T cancelNative(T token) {
        Entry<T> entry = byToken.get(token);
        return entry == null ? null : retire(entry);
    }

    /**
     * Returns the token once Flutter already acknowledged it, meaning the stroke is fully
     * retirable. The entry stays tracked until {@link #confirmRetired} (see {@link #acknowledge}
     * for why).
     */
    @Nullable T markNativeFinished(T token) {
        Entry<T> entry = byToken.get(token);
        if (entry == null) return null;
        entry.nativeFinished = true;
        return entry.acknowledged ? entry.token : null;
    }

    /**
     * Called from the deferred frame callback to actually remove a retirable entry. Returns
     * the token if it was still tracked, or null if something else already removed it (for
     * example a cancellation racing ahead of the deferred callback) so the caller can skip a
     * redundant clear.
     */
    @Nullable T confirmRetired(T token) {
        Entry<T> entry = byToken.get(token);
        return entry == null ? null : retire(entry);
    }

    private T retire(Entry<T> entry) {
        byTimestamp.remove(entry.sourceTimestampUs);
        byToken.remove(entry.token);
        return entry.token;
    }

    /**
     * A one-frame overlap of wet and dry ink is acceptable; a gap is not. Flutter's
     * acknowledgement only means its frame was recorded, not presented, so the native clear is
     * delayed one vsync to give that frame a chance to reach the screen first.
     */
    static void deferClear(Runnable clearWetInk) {
        Choreographer.getInstance().postFrameCallback(frameTimeNanos -> clearWetInk.run());
    }
}
