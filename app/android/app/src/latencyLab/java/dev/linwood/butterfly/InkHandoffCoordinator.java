package dev.linwood.butterfly;

import androidx.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Bounded matching between native DOWN events and Dart paint acknowledgements. */
final class InkHandoffCoordinator<T> {
    private static final long SOURCE_TIME_TOLERANCE_US = 2_000L;
    private static final int MAX_RETAINED = 16;

    static final class Registration {
        final long generation;
        final long sequence;
        final long sourceTimestampUs;

        Registration(long generation, long sequence, long sourceTimestampUs) {
            this.generation = generation;
            this.sequence = sequence;
            this.sourceTimestampUs = sourceTimestampUs;
        }
    }

    private static final class Key {
        final long generation;
        final long sequence;
        final long sourceTimestampUs;

        Key(long generation, long sequence, long sourceTimestampUs) {
            this.generation = generation;
            this.sequence = sequence;
            this.sourceTimestampUs = sourceTimestampUs;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key key = (Key) other;
            return generation == key.generation && sequence == key.sequence
                    && sourceTimestampUs == key.sourceTimestampUs;
        }

        @Override public int hashCode() {
            int result = Long.hashCode(generation);
            result = 31 * result + Long.hashCode(sequence);
            return 31 * result + Long.hashCode(sourceTimestampUs);
        }
    }

    private static final class Entry<T> {
        final T token;
        final long generation;
        final long sourceTimestampUs;
        Registration registration;
        boolean finished;
        boolean acknowledged;

        Entry(T token, long generation, long sourceTimestampUs) {
            this.token = token;
            this.generation = generation;
            this.sourceTimestampUs = sourceTimestampUs;
        }
    }

    private final Deque<Entry<T>> entries = new ArrayDeque<>();
    private final Deque<Registration> pendingRegistrations = new ArrayDeque<>();
    private final Map<T, Entry<T>> byToken = new HashMap<>();
    private final Map<Long, Entry<T>> bySequence = new HashMap<>();
    private final Map<Key, Boolean> pendingAcks = new HashMap<>();
    private final Map<Key, Boolean> pendingCancels = new HashMap<>();
    private final Deque<T> evicted = new ArrayDeque<>();
    private long generation = Long.MIN_VALUE;
    private boolean quarantined;

    void setGeneration(long value) {
        clear();
        generation = value;
        quarantined = false;
    }

    void addNativeStroke(long expectedGeneration, long sourceTimestampUs, T token) {
        if (quarantined || expectedGeneration != generation || token == null
                || sourceTimestampUs < 0) return;
        if (byToken.containsKey(token)) {
            quarantineAndEvict();
            return;
        }
        Entry<T> entry = new Entry<>(token, expectedGeneration, sourceTimestampUs);
        entries.addLast(entry);
        byToken.put(token, entry);
        Registration registration = takeClosestPending(expectedGeneration, sourceTimestampUs);
        if (registration != null) attach(entry, registration);
        if (entries.size() > MAX_RETAINED) quarantineAndEvict();
    }

    void register(Registration registration) {
        if (quarantined || registration == null || registration.generation != generation
                || registration.sequence <= 0 || registration.sourceTimestampUs < 0) return;
        if (bySequence.containsKey(registration.sequence)
                || containsPendingSequence(registration.sequence)) {
            quarantineAndEvict();
            return;
        }
        Key key = key(registration);
        if (pendingCancels.remove(key) != null) {
            Entry<T> candidate = closestUnregistered(registration.generation,
                    registration.sourceTimestampUs);
            if (candidate != null) evict(candidate);
            return;
        }
        Entry<T> candidate = closestUnregistered(registration.generation,
                registration.sourceTimestampUs);
        if (candidate != null) {
            attach(candidate, registration);
        } else {
            pendingRegistrations.addLast(registration);
            if (pendingRegistrations.size() > MAX_RETAINED) quarantineAndEvict();
        }
    }

    @Nullable T markNativeFinished(T token) {
        Entry<T> entry = byToken.get(token);
        if (entry == null) return null;
        entry.finished = true;
        return entry.acknowledged ? retire(entry) : null;
    }

    @Nullable T acknowledge(long expectedGeneration, long sequence, long sourceTimestampUs,
            String elementId, int pointCount) {
        if (quarantined || expectedGeneration != generation || sequence <= 0
                || sourceTimestampUs < 0 || elementId == null || elementId.isEmpty()
                || pointCount <= 1) return null;
        Entry<T> entry = bySequence.get(sequence);
        if (entry == null) {
            Key key = new Key(expectedGeneration, sequence, sourceTimestampUs);
            if (!pendingCancels.containsKey(key)) {
                pendingAcks.put(key, Boolean.TRUE);
                if (pendingAcks.size() + pendingCancels.size() > MAX_RETAINED) {
                    quarantineAndEvict();
                }
            }
            return null;
        }
        if (entry.registration.sourceTimestampUs != sourceTimestampUs) {
            quarantineAndEvict();
            return null;
        }
        entry.acknowledged = true;
        return entry.finished ? retire(entry) : null;
    }

    @Nullable T cancel(long expectedGeneration, long sequence, long sourceTimestampUs) {
        if (quarantined || expectedGeneration != generation || sequence <= 0
                || sourceTimestampUs < 0) return null;
        Key key = new Key(expectedGeneration, sequence, sourceTimestampUs);
        pendingAcks.remove(key);
        removePending(key);
        Entry<T> entry = bySequence.get(sequence);
        if (entry == null) {
            pendingCancels.put(key, Boolean.TRUE);
            if (pendingAcks.size() + pendingCancels.size() > MAX_RETAINED) {
                quarantineAndEvict();
            }
            return null;
        }
        if (entry.registration.sourceTimestampUs != sourceTimestampUs) {
            quarantineAndEvict();
            return null;
        }
        return retire(entry);
    }

    void cancelNative(T token) {
        Entry<T> entry = byToken.get(token);
        if (entry != null) retire(entry);
    }

    int retainedCount() { return entries.size(); }
    boolean isQuarantined() { return quarantined; }

    List<T> drainEvictedTokens() {
        if (evicted.isEmpty()) return Collections.emptyList();
        List<T> result = new ArrayList<>(evicted);
        evicted.clear();
        return result;
    }

    void clear() {
        entries.clear();
        pendingRegistrations.clear();
        byToken.clear();
        bySequence.clear();
        pendingAcks.clear();
        pendingCancels.clear();
        evicted.clear();
    }

    private void attach(Entry<T> entry, Registration registration) {
        entry.registration = registration;
        bySequence.put(registration.sequence, entry);
        Key key = key(registration);
        if (pendingCancels.remove(key) != null) {
            evict(entry);
        } else if (pendingAcks.remove(key) != null) {
            entry.acknowledged = true;
            if (entry.finished) evict(entry);
        }
    }

    @Nullable private Entry<T> closestUnregistered(long expectedGeneration,
            long sourceTimestampUs) {
        Entry<T> best = null;
        long bestDistance = Long.MAX_VALUE;
        for (Entry<T> entry : entries) {
            if (entry.generation != expectedGeneration || entry.registration != null) continue;
            long distance = Math.abs(entry.sourceTimestampUs - sourceTimestampUs);
            if (distance <= SOURCE_TIME_TOLERANCE_US && distance < bestDistance) {
                best = entry;
                bestDistance = distance;
            }
        }
        return best;
    }

    @Nullable private Registration takeClosestPending(long expectedGeneration,
            long sourceTimestampUs) {
        Registration best = null;
        long bestDistance = Long.MAX_VALUE;
        for (Registration registration : pendingRegistrations) {
            if (registration.generation != expectedGeneration) continue;
            long distance = Math.abs(registration.sourceTimestampUs - sourceTimestampUs);
            if (distance <= SOURCE_TIME_TOLERANCE_US && distance < bestDistance) {
                best = registration;
                bestDistance = distance;
            }
        }
        if (best != null) pendingRegistrations.remove(best);
        return best;
    }

    private boolean containsPendingSequence(long sequence) {
        for (Registration registration : pendingRegistrations) {
            if (registration.sequence == sequence) return true;
        }
        return false;
    }

    private void removePending(Key key) {
        Iterator<Registration> iterator = pendingRegistrations.iterator();
        while (iterator.hasNext()) {
            Registration registration = iterator.next();
            if (key.equals(key(registration))) {
                iterator.remove();
                return;
            }
        }
    }

    private static Key key(Registration registration) {
        return new Key(registration.generation, registration.sequence,
                registration.sourceTimestampUs);
    }

    private void evict(Entry<T> entry) {
        T token = retire(entry);
        if (token != null) evicted.addLast(token);
    }

    @Nullable private T retire(Entry<T> entry) {
        if (!entries.remove(entry)) return null;
        byToken.remove(entry.token);
        if (entry.registration != null) bySequence.remove(entry.registration.sequence);
        return entry.token;
    }

    private void quarantineAndEvict() {
        quarantined = true;
        for (Entry<T> entry : entries) evicted.addLast(entry.token);
        entries.clear();
        pendingRegistrations.clear();
        byToken.clear();
        bySequence.clear();
        pendingAcks.clear();
        pendingCancels.clear();
    }
}
