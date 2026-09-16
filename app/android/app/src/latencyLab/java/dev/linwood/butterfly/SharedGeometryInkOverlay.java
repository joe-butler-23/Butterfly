package dev.linwood.butterfly;

import android.content.Context;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Build;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.MotionPredictor;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.graphics.lowlatency.CanvasFrontBufferedRenderer;
import androidx.graphics.surface.SurfaceControlCompat;
import androidx.input.motionprediction.MotionEventPredictor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/** Front-buffered transient ink using the same perfect_freehand geometry as Flutter. */
final class SharedGeometryInkOverlay implements StylusWetInkRenderer {
    private static final String TAG = "ButterflyInk";
    private static final int CHECKPOINT_POINTS = 16;
    private static final Executor DIRECT = Runnable::run;
    // The predicted tail is drawn at this fraction of the stroke colour's full alpha so an
    // over-running prediction reads visually as a hint, not committed ink.
    static final float PREDICTED_TAIL_ALPHA_FRACTION = 0.45f;

    private enum Kind { STROKE, PREFLIGHT, ANDROIDX_CONTROL }

    private static final class Policy {
        final int argb;
        final float width, thinning, smoothing, streamline;
        final boolean firstPressure;

        Policy(int argb, float width, float thinning, float smoothing, float streamline,
                boolean firstPressure) {
            this.argb = argb;
            this.width = width;
            this.thinning = thinning;
            this.smoothing = smoothing;
            this.streamline = streamline;
            this.firstPressure = firstPressure;
        }
    }

    /**
     * What crosses to the renderer thread for one front-buffer draw: only the raw points newly
     * appended since the last drain (never the whole stroke -- see {@link #drainedCount}), plus
     * at most one transient predicted sample. {@link Policy} is only actually read the first time
     * a given {@code token} reaches the renderer, to construct that stroke's
     * {@link PerfectFreehandGeometry.IncrementalOutline}.
     */
    private static final class Snapshot {
        final long token, generation;
        final List<PerfectFreehandGeometry.Point> newPoints;
        @Nullable final PerfectFreehandGeometry.Point predicted;
        final Policy policy;
        final boolean complete, checkpoint;

        Snapshot(long token, long generation, List<PerfectFreehandGeometry.Point> newPoints,
                @Nullable PerfectFreehandGeometry.Point predicted, Policy policy, boolean complete,
                boolean checkpoint) {
            this.token = token;
            this.generation = generation;
            this.newPoints = newPoints;
            this.predicted = predicted;
            this.policy = policy;
            this.complete = complete;
            this.checkpoint = checkpoint;
        }
    }

    /** Coalesces same-frame submissions for one token; carries no points -- see {@link #drainLatest}. */
    private static final class PendingStroke {
        final long token, generation;
        final Policy policy;
        boolean complete, checkpoint;

        PendingStroke(long token, long generation, Policy policy, boolean complete,
                boolean checkpoint) {
            this.token = token;
            this.generation = generation;
            this.policy = policy;
            this.complete = complete;
            this.checkpoint = checkpoint;
        }
    }

    private static final class Request {
        @Nullable final Snapshot snapshot;
        final Kind kind;
        final long generation, readinessEpoch;

        private Request(@Nullable Snapshot snapshot, Kind kind, long generation,
                long readinessEpoch) {
            this.snapshot = snapshot;
            this.kind = kind;
            this.generation = generation;
            this.readinessEpoch = readinessEpoch;
        }

        static Request stroke(Snapshot snapshot) {
            return new Request(snapshot, Kind.STROKE, snapshot.generation, Long.MIN_VALUE);
        }

        static Request preflight(long generation, long readinessEpoch) {
            return new Request(null, Kind.PREFLIGHT, generation, readinessEpoch);
        }
    }

    private static final class CallbackRecord {
        @Nullable final Snapshot snapshot;
        final Kind kind;
        final long generation, readinessEpoch;
        final boolean successful;

        CallbackRecord(@Nullable Snapshot snapshot, Kind kind, long generation,
                long readinessEpoch, boolean successful) {
            this.snapshot = snapshot;
            this.kind = kind;
            this.generation = generation;
            this.readinessEpoch = readinessEpoch;
            this.successful = successful;
        }
    }

    private final SurfaceView view;
    private final View flutterInputView;
    private final SurfaceHolder.Callback surfaceCallback;
    private final int[] location = new int[2];
    private final InkHandoffCoordinator<Long> handoff = new InkHandoffCoordinator<>();
    private final ArrayList<PerfectFreehandGeometry.Point> points = new ArrayList<>();
    private final ConcurrentLinkedQueue<CallbackRecord> frontCallbacks =
            new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<CallbackRecord> multiCallbacks =
            new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingFront = new AtomicInteger();
    private final AtomicInteger pendingMulti = new AtomicInteger();
    private final AtomicInteger pendingTransactions = new AtomicInteger();
    private final AtomicInteger pendingClear = new AtomicInteger();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // Same colour as `paint` but at PREDICTED_TAIL_ALPHA_FRACTION alpha, so an
    // over-running prediction reads as a hint rather than committed ink; kept as a
    // separate Paint so the real tail stays fully opaque.
    private final Paint predictedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // Reused instead of allocating a Path per frame. Safe because every read/write of this field,
    // and of `engine`/`engineToken` below, happens inside the RendererCallback methods, which are
    // only ever invoked serially on the renderer's single dedicated background thread.
    private final Path scratchPath = new Path();
    @Nullable private PerfectFreehandGeometry.IncrementalOutline engine;
    private long engineToken = Long.MIN_VALUE;

    @Nullable private CanvasFrontBufferedRenderer<Request> renderer;
    @Nullable private RectF bounds;
    @Nullable private Policy policy;
    @Nullable private PendingStroke pendingSnapshot;
    @Nullable private Snapshot latestSnapshot;
    @Nullable private ActivationCallback activationCallback;
    @Nullable private FailureCallback failureCallback;
    @Nullable private StrokeAbortedCallback strokeAbortedCallback;
    @Nullable private MotionEventPredictor predictor;
    @Nullable private PlatformPredictor platformPredictor;
    @Nullable private PerfectFreehandGeometry.Point pendingPredicted;
    @Nullable private PerfectFreehandGeometry.Point lastInputPoint;
    private boolean drainPosted, frontRenderOutstanding;
    private boolean configured, enabled, attached, surfaceReady, rendererControlReady, ready;
    private boolean surfaceCallbackRegistered, failed, clearRequested;
    private long generation = Long.MIN_VALUE;
    private long readinessEpoch, preflightEpoch = Long.MIN_VALUE, prewarmedGeneration = Long.MIN_VALUE;
    private long nextToken, activeToken = Long.MIN_VALUE;
    private long activeSourceTimestampUs = Long.MIN_VALUE;
    private int activePointer = -1;
    // How many of `points` the renderer thread has already seen; only the points beyond this
    // cursor are copied across on the next drain (see drainLatest()).
    private int drainedCount;
    // Total points appended to the current stroke, independent of `points`'s own size: drainLatest
    // clears `points` down to just the undrained tail after every drain (see drainLatest()) so the
    // buffer never grows for the life of a long stroke, but the degenerate-stroke check in
    // appendEvent and the checkpoint cadence below both need the stroke-wide count, not the size
    // of that trimmed buffer.
    private int totalPointCount;
    // Milliseconds of motion prediction to request; 0 disables prediction entirely. Read once
    // from the activity intent's PREDICTION_MS extra and fixed for this overlay's lifetime.
    private final int predictionHorizonMs;

    /**
     * Isolates the API 34 android.view.MotionPredictor reference in its own class so that class
     * is only loaded/verified when actually instantiated on SDK_INT >= 34 (see usePlatformPredictor()
     * below); on lower API levels {@link #predictor}, the androidx MotionEventPredictor, is used
     * instead and this class is never touched.
     */
    private static final class PlatformPredictor {
        private final MotionPredictor predictor;
        PlatformPredictor(Context context) { predictor = new MotionPredictor(context); }
        void record(MotionEvent event) { predictor.record(event); }
        @Nullable MotionEvent predict(long predictionTimeNanos) {
            return predictor.predict(predictionTimeNanos);
        }
    }

    SharedGeometryInkOverlay(Context context, View flutterInputView, int predictionMs) {
        this.flutterInputView = flutterInputView;
        this.predictionHorizonMs = Math.max(0, predictionMs);
        view = new SurfaceView(context);
        view.setZOrderOnTop(true);
        view.getHolder().setFormat(PixelFormat.TRANSLUCENT);
        view.setClickable(false);
        view.setFocusable(false);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        surfaceCallback = new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                SharedGeometryInkOverlay.this.surfaceChanged(holder.getSurface().isValid());
            }
            @Override public void surfaceChanged(SurfaceHolder holder, int format, int width,
                    int height) {
                SharedGeometryInkOverlay.this.surfaceChanged(holder.getSurface().isValid() && width > 0 && height > 0);
            }
            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                SharedGeometryInkOverlay.this.surfaceChanged(false);
                if (enabled) quarantine("surface destroyed while enabled");
                else resolveActivation(false);
            }
        };
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {}
            // The surface may never exist if the view is detached first; resolve the pending
            // activation either way so Dart is never left awaiting forever.
            @Override public void onViewDetachedFromWindow(View v) { resolveActivation(false); }
        });
    }

    @Override public View getView() { return view; }

    @Override public void setFailureCallback(FailureCallback callback) {
        failureCallback = callback;
    }

    @Override public void setStrokeAbortedCallback(StrokeAbortedCallback callback) {
        strokeAbortedCallback = callback;
    }

    @Override public boolean configure(Object arguments) {
        if (failed) return false;
        disable();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false;
        Map<?, ?> state = map(arguments);
        Number version = state == null ? null : number(state.get("protocolVersion"));
        Number nextGeneration = state == null ? null : number(state.get("generation"));
        Number dpr = state == null ? null : number(state.get("devicePixelRatio"));
        RectF parsedBounds = state == null || dpr == null ? null
                : parseBounds(map(state.get("canvasBounds")), dpr.floatValue());
        Policy parsedPolicy = state == null || dpr == null ? null
                : parsePolicy(map(state.get("geometry")), dpr.floatValue());
        if (version == null || version.intValue() != 1 || nextGeneration == null
                || !bool(state.get("enabled")) || !bool(state.get("eligible"))
                || parsedBounds == null || parsedPolicy == null) return false;
        generation = nextGeneration.longValue();
        bounds = parsedBounds;
        policy = parsedPolicy;
        handoff.setGeneration(generation);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(parsedPolicy.argb);
        predictedPaint.setStyle(Paint.Style.FILL);
        predictedPaint.setColor(predictedTailColor(parsedPolicy.argb));
        configured = true;
        return true;
    }

    @Override public boolean enable(Object arguments) {
        Map<?, ?> state = map(arguments);
        Number requested = state == null ? null : number(state.get("generation"));
        if (!configured || failed || requested == null || requested.longValue() != generation) {
            return false;
        }
        enabled = true;
        return true;
    }

    @Override public boolean attachAndPrewarm() {
        if (!configured || !enabled || failed || !applyBounds()) return false;
        attached = true;
        try {
            if (renderer == null) {
                SurfaceHolder holder = view.getHolder();
                surfaceChanged(holder.getSurface().isValid()
                        && view.getWidth() > 0 && view.getHeight() > 0);
                renderer = new CanvasFrontBufferedRenderer<>(view, new RendererCallback());
                holder.addCallback(surfaceCallback);
                surfaceCallbackRegistered = true;
            }
            view.setVisibility(View.VISIBLE);
            maybeReady();
            return true;
        } catch (RuntimeException | LinkageError error) {
            quarantine("attachAndPrewarm threw", error);
            return false;
        }
    }

    @Override public void awaitActivation(ActivationCallback callback) {
        activationCallback = callback;
        maybeReady();
    }

    private void surfaceChanged(boolean valid) {
        surfaceReady = valid;
        rendererControlReady = false;
        ready = false;
        readinessEpoch++;
        preflightEpoch = Long.MIN_VALUE;
        maybeReady();
    }

    private void maybeReady() {
        if (!enabled || !configured || failed || !attached || !surfaceReady
                || !view.isLaidOut() || view.getWidth() <= 0 || view.getHeight() <= 0
                || renderer == null || !rendererControlReady) return;
        if (ready) {
            resolveActivation(true);
            return;
        }
        if (prewarmedGeneration != generation) {
            prewarmedGeneration = generation;
            prewarmGeometry();
        }
        long epoch = readinessEpoch;
        if (preflightEpoch == epoch) return;
        preflightEpoch = epoch;
        pendingMulti.incrementAndGet();
        try {
            renderer.renderMultiBufferedLayer(Collections.singleton(
                    Request.preflight(generation, epoch)));
        } catch (RuntimeException | LinkageError error) {
            pendingMulti.decrementAndGet();
            quarantine("renderMultiBufferedLayer(preflight) threw", error);
        }
    }

    private void resolveActivation(boolean value) {
        ActivationCallback callback = activationCallback;
        if (callback == null) return;
        activationCallback = null;
        callback.onActivated(value);
    }

    @Override public void onMotionEvent(@NonNull MotionEvent event) {
        if (!enabled || failed || !ready) return;
        int action = event.getActionMasked();
        if (clearRequested || pendingClear.get() != 0) {
            return;
        }
        int index = event.getActionIndex();
        boolean active = activePointer != -1;
        if (action == MotionEvent.ACTION_CANCEL
                || (event.getFlags() & MotionEvent.FLAG_CANCELED) != 0
                || event.getPointerCount() != 1 || index != 0
                || !eligibleSample(event, index)) {
            if (active) {
                abortStroke("event gate failed: action=" + action
                        + " canceledFlag=" + ((event.getFlags() & MotionEvent.FLAG_CANCELED) != 0)
                        + " pointerCount=" + event.getPointerCount() + " index=" + index
                        + " eligible=" + eligibleSample(event, index));
            }
            return;
        }
        view.getLocationInWindow(location);
        float locationX = location[0];
        float locationY = location[1];
        recordPrediction(event, action, locationX, locationY);
        int pointer = event.getPointerId(index);
        switch (action) {
            case MotionEvent.ACTION_DOWN -> begin(event, pointer, locationX, locationY);
            // A per-sample bounds check inside appendEvent drops any out-of-canvas point
            // individually, the same way Flutter's PenHandler.addPoint does, instead of ending
            // the stroke; only a pointer identity mismatch (unsupported multi-touch) aborts here.
            case MotionEvent.ACTION_MOVE -> {
                if (activePointer == -1) {
                    return;
                } else if (pointer != activePointer) {
                    abortStroke("MOVE pointer id " + pointer + " != active " + activePointer);
                } else if (!appendEvent(event, index, false, locationX, locationY)) {
                    abortStroke("appendEvent rejected a MOVE sample (non-finite coordinate)");
                }
            }
            case MotionEvent.ACTION_UP -> {
                if (activePointer == -1) {
                    return;
                } else if (pointer != activePointer) {
                    abortStroke("UP pointer id " + pointer + " != active " + activePointer);
                } else if (appendEvent(event, index, true, locationX, locationY)) {
                    activePointer = -1;
                    predictor = null;
                    platformPredictor = null;
                } else {
                    abortStroke("appendEvent rejected the final UP sample (non-finite coordinate)");
                }
            }
            default -> {
                if (active) abortStroke("unhandled MotionEvent action=" + action);
            }
        }
    }

    private boolean usePlatformPredictor() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
    }

    /**
     * The predicted tail's paint colour: {@code argb} at {@link #PREDICTED_TAIL_ALPHA_FRACTION}
     * of full alpha instead of its own alpha channel. Policy colours are always fully opaque
     * (enforced by {@link #parsePolicy}), so this only ever lowers alpha, never raises it.
     * Package-visible and static so it is unit-testable without an Android runtime.
     */
    static int predictedTailColor(int argb) {
        int alpha = Math.round(0xFF * PREDICTED_TAIL_ALPHA_FRACTION);
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }

    /**
     * The absolute target time to hand {@link android.view.MotionPredictor#predict(long)}:
     * {@code horizonMs} ahead of the latest real sample's own timestamp, in the same
     * uptimeMillis-based nanosecond time base {@code predict()} expects -- not wall-clock 'now'
     * at the moment prediction happens to run, which can drift from the event's timestamp under
     * dispatch or GC jitter. Package-visible and static so it is unit-testable without an
     * Android runtime.
     */
    static long predictionTimeNanos(long eventTimeMillis, int horizonMs) {
        return (eventTimeMillis + horizonMs) * 1_000_000L;
    }

    /**
     * Records every filtered event into the motion predictor and, once per MOVE, asks it for one
     * predicted sample -- on API 34+ via the platform {@link android.view.MotionPredictor}
     * (wrapped in {@link PlatformPredictor} so that class is never loaded on lower API levels),
     * and on older devices via the androidx {@link MotionEventPredictor} fallback. A horizon of
     * 0 (see {@link #predictionHorizonMs}) disables prediction outright: neither predictor is
     * ever touched and {@link #pendingPredicted} is never set. Either way the prediction is
     * staged as a transient point that {@link #drainLatest} attaches to the next front-buffer
     * draw and that is never added to {@link #points}, so a wrong guess can only ever affect one
     * frame's pixels, not the stroke's persisted geometry.
     */
    private void recordPrediction(MotionEvent event, int action, float locationX, float locationY) {
        if (predictionHorizonMs <= 0) return;
        MotionEvent copy = null;
        MotionEvent prediction = null;
        try {
            copy = MotionEvent.obtain(event);
            copy.offsetLocation(-locationX, -locationY);
            if (usePlatformPredictor()) {
                if (platformPredictor == null) platformPredictor = new PlatformPredictor(
                        view.getContext());
                platformPredictor.record(copy);
                if (action != MotionEvent.ACTION_MOVE) return;
                prediction = platformPredictor.predict(
                        predictionTimeNanos(event.getEventTime(), predictionHorizonMs));
            } else {
                if (predictor == null) predictor = MotionEventPredictor.newInstance(view);
                predictor.record(copy);
                if (action != MotionEvent.ACTION_MOVE) return;
                prediction = predictor.predict();
            }
            if (prediction == null) return;
            float x = prediction.getX(0);
            float y = prediction.getY(0);
            if (!Float.isFinite(x) || !Float.isFinite(y)) return;
            InputDevice device = event.getDevice();
            InputDevice.MotionRange range = device == null ? null
                    : device.getMotionRange(MotionEvent.AXIS_PRESSURE);
            float pressure = normalizedPressure(prediction.getPressure(0),
                    range == null ? 0f : range.getMin(), range == null ? 1f : range.getMax());
            if (!Float.isFinite(pressure)) return;
            pendingPredicted = new PerfectFreehandGeometry.Point(x, y, (double) pressure);
        } catch (RuntimeException | LinkageError error) {
            // Prediction is a purely cosmetic latency hint; a device/library quirk here should
            // not take down the whole overlay the way a geometry failure does.
            predictor = null;
            platformPredictor = null;
        } finally {
            if (copy != null) copy.recycle();
            if (prediction != null) prediction.recycle();
        }
    }

    private void begin(MotionEvent event, int pointer, float locationX, float locationY) {
        if (activePointer != -1) {
            abortStroke("new DOWN while a stroke (pointer " + activePointer + ") was active");
            return;
        }
        if (handoff.retainedCount() != 0 || !inCanvas(event, 0)) {
            return;
        }
        activePointer = pointer;
        activeToken = ++nextToken;
        activeSourceTimestampUs = event.getEventTime() * 1_000L;
        points.clear();
        drainedCount = 0;
        totalPointCount = 0;
        lastInputPoint = null;
        pendingPredicted = null;
        // Info level: the tablet drops debug lines globally (log.tag=I).
        Log.i(TAG, "stroke token=" + activeToken + " prediction horizon="
                + (predictionHorizonMs <= 0 ? "disabled" : predictionHorizonMs + "ms via "
                        + (usePlatformPredictor() ? "platform MotionPredictor"
                                : "androidx MotionEventPredictor")));
        // retainedCount() == 0 was just checked above, so this insert can never overflow
        // the bounded map; there is no per-token buffer here to clear if it somehow did.
        handoff.addNativeStroke(generation, activeSourceTimestampUs, activeToken);
        flutterInputView.requestUnbufferedDispatch(event);
        if (!appendEvent(event, 0, false, locationX, locationY)) {
            abortStroke("initial DOWN sample rejected by appendEvent");
        }
    }

    private boolean appendEvent(MotionEvent event, int pointerIndex, boolean complete,
            float locationX, float locationY) {
        int initialPointCount = totalPointCount;
        // The device's pressure range is the same for every sample in one MotionEvent, so look
        // it up once here instead of once per historical + trailing sample.
        InputDevice device = event.getDevice();
        InputDevice.MotionRange range = device == null ? null
                : device.getMotionRange(MotionEvent.AXIS_PRESSURE);
        float minimum = range == null ? 0f : range.getMin();
        float maximum = range == null ? 1f : range.getMax();
        for (int history = 0; history < event.getHistorySize(); history++) {
            float x = event.getHistoricalX(pointerIndex, history);
            float y = event.getHistoricalY(pointerIndex, history);
            // Drop an out-of-canvas historical sample individually rather than aborting the
            // whole batch or the stroke, matching Flutter's PenHandler.addPoint.
            if (!inCanvas(x, y)) continue;
            if (!appendPoint(x, y, normalizedPressure(
                    event.getHistoricalPressure(pointerIndex, history), minimum, maximum),
                    locationX, locationY)) return false;
        }
        float x = event.getX(pointerIndex);
        float y = event.getY(pointerIndex);
        if (inCanvas(x, y) && !appendPoint(x, y,
                normalizedPressure(event.getPressure(pointerIndex), minimum, maximum),
                locationX, locationY)) {
            return false;
        }
        // A one-point outline is drawable, so submit the DOWN immediately. This keeps a contact
        // dot native-owned while Flutter's normal one-point/tap completion path remains intact.
        if (totalPointCount == 0) return !complete;
        boolean changed = totalPointCount != initialPointCount;
        if (changed || complete) {
            submit(complete, !complete && totalPointCount / CHECKPOINT_POINTS
                    > initialPointCount / CHECKPOINT_POINTS);
        }
        return true;
    }

    private boolean appendPoint(float windowX, float windowY, float pressure,
            float locationX, float locationY) {
        float x = windowX - locationX;
        float y = windowY - locationY;
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(pressure)) {
            return false;
        }
        if (lastInputPoint != null) {
            PerfectFreehandGeometry.Point last = lastInputPoint;
            if (last.x == x && last.y == y) return true;
        }
        // No length-based abort here: a stroke is never ended just because it ran long. The
        // incremental engine on the renderer thread only ever sees the undrained tail (see
        // drainLatest(), which trims `points` back down after every drain), so memory stays
        // bounded by drain frequency, not by capping how many points a stroke may contain.
        lastInputPoint = new PerfectFreehandGeometry.Point(x, y, (double) pressure);
        points.add(lastInputPoint);
        totalPointCount++;
        return true;
    }

    private void submit(boolean complete, boolean checkpoint) {
        Policy currentPolicy = policy;
        if (currentPolicy == null || activeToken == Long.MIN_VALUE) {
            quarantine("submit() called with policy=" + currentPolicy + " activeToken="
                    + activeToken);
            return;
        }
        PendingStroke pending = pendingSnapshot;
        if (pending != null && pending.token == activeToken
                && pending.generation == generation) {
            pending.complete |= complete;
            pending.checkpoint |= checkpoint;
        } else {
            pendingSnapshot = new PendingStroke(activeToken, generation, currentPolicy,
                    complete, checkpoint);
        }
        if (frontRenderOutstanding || drainPosted || pendingFront.get() != 0
                || pendingMulti.get() != 0) return;
        drainPosted = true;
        // The check just above guarantees no front-buffer render is currently outstanding, so
        // every submission -- not only the stroke's first -- renders immediately, right here on
        // the UI thread: the first wet pixel, and every later one, is never delayed waiting for a
        // vsync that isn't needed. A submission that arrives WHILE a render is in flight instead
        // takes the early return above and is coalesced into pendingSnapshot (latest state wins);
        // the in-flight render's completion callback (completeFrontRender) calls drainLatest()
        // again once it finishes, picking up whatever coalesced while it was busy.
        drainLatest();
    }

    private void drainLatest() {
        if (frontRenderOutstanding || pendingFront.get() != 0 || pendingMulti.get() != 0) {
            return;
        }
        drainPosted = false;
        PendingStroke pending = pendingSnapshot;
        pendingSnapshot = null;
        Snapshot snapshot = null;
        if (pending != null) {
            // Only the points appended since the last drain cross to the renderer thread; the
            // engine living there keeps its own running geometry state, so it never needs the
            // whole stroke again (see PerfectFreehandGeometry.IncrementalOutline). Every point
            // from drainedCount to the current end is being drained right now, so instead of just
            // advancing the cursor, the drained prefix is physically removed from `points` and
            // the cursor reset to 0: `points` then only ever holds however many samples arrived
            // since the last drain (bounded by drain frequency), not the whole stroke, however
            // long it runs -- see totalPointCount for the stroke-wide count this no longer tracks.
            List<PerfectFreehandGeometry.Point> newPoints =
                    new ArrayList<>(points.subList(drainedCount, points.size()));
            points.clear();
            drainedCount = 0;
            PerfectFreehandGeometry.Point predicted = pendingPredicted;
            pendingPredicted = null;
            snapshot = new Snapshot(pending.token, pending.generation, newPoints, predicted,
                    pending.policy, pending.complete, pending.checkpoint);
        }
        CanvasFrontBufferedRenderer<Request> currentRenderer = renderer;
        if (snapshot == null || currentRenderer == null || failed || !enabled
                || snapshot.generation != generation) {
            if (snapshot != null) {
                Log.d(TAG, "drainLatest: dropped snapshot token=" + snapshot.token
                        + " rendererNull=" + (currentRenderer == null) + " failed=" + failed
                        + " enabled=" + enabled + " snapshotGen=" + snapshot.generation
                        + " currentGen=" + generation);
            }
            return;
        }
        latestSnapshot = snapshot;
        frontRenderOutstanding = true;
        try {
            currentRenderer.renderFrontBufferedLayer(Request.stroke(snapshot));
        } catch (RuntimeException | LinkageError error) {
            frontRenderOutstanding = false;
            quarantine("renderFrontBufferedLayer threw for token=" + snapshot.token, error);
        }
    }

    private void completeFrontRender(CallbackRecord record) {
        Snapshot snapshot = record.snapshot;
        if (snapshot == null || !record.successful || failed || !enabled
                || snapshot.generation != generation) {
            Log.w(TAG, "completeFrontRender: dropping record token="
                    + (snapshot == null ? "null" : snapshot.token) + " successful="
                    + record.successful + " failed=" + failed + " enabled=" + enabled
                    + " snapshotGen=" + (snapshot == null ? "n/a" : snapshot.generation)
                    + " currentGen=" + generation);
            frontRenderOutstanding = false;
            return;
        }
        if (snapshot.complete || snapshot.checkpoint) {
            CanvasFrontBufferedRenderer<Request> currentRenderer = renderer;
            if (currentRenderer == null) {
                frontRenderOutstanding = false;
                quarantine("completeFrontRender: renderer null at commit time, token="
                        + snapshot.token);
                return;
            }
            pendingMulti.incrementAndGet();
            try {
                currentRenderer.commit();
            } catch (RuntimeException | LinkageError error) {
                pendingMulti.decrementAndGet();
                frontRenderOutstanding = false;
                quarantine("commit() threw for token=" + snapshot.token, error);
                return;
            }
        }
        frontRenderOutstanding = false;
        drainLatest();
        maybeIssueClear();
    }

    private void abortStroke(String reason) {
        long token = activeToken;
        long sourceTimestampUs = activeSourceTimestampUs;
        Log.w(TAG, "abortStroke: " + reason + " token=" + token);
        activePointer = -1;
        activeToken = Long.MIN_VALUE;
        activeSourceTimestampUs = Long.MIN_VALUE;
        points.clear();
        drainedCount = 0;
        totalPointCount = 0;
        lastInputPoint = null;
        pendingPredicted = null;
        predictor = null;
        platformPredictor = null;
        pendingSnapshot = null;
        if (token == Long.MIN_VALUE) return;
        if (handoff.cancelNative(token) != null && sourceTimestampUs != Long.MIN_VALUE) {
            StrokeAbortedCallback callback = strokeAbortedCallback;
            if (callback != null) callback.onAborted(generation, sourceTimestampUs);
        }
        Snapshot snapshot = latestSnapshot;
        if (snapshot != null && snapshot.token == token) {
            clearRequested = true;
            maybeIssueClear();
        }
    }

    @Override public boolean registerStroke(Object arguments) {
        Map<?, ?> state = map(arguments);
        Number requested = state == null ? null : number(state.get("generation"));
        Number source = state == null ? null : number(state.get("sourceTimestampUs"));
        if (requested == null || source == null || requested.longValue() != generation) return false;
        if (!enabled || clearRequested || pendingClear.get() != 0) {
            handoff.cancel(requested.longValue(), source.longValue());
            return false;
        }
        return handoff.register(requested.longValue(), source.longValue());
    }

    @Override public void acknowledgeStroke(Object arguments, Runnable completion) {
        Map<?, ?> state = map(arguments);
        Number requested = state == null ? null : number(state.get("generation"));
        Number source = state == null ? null : number(state.get("sourceTimestampUs"));
        Number count = state == null ? null : number(state.get("finalPointCount"));
        Object id = state == null ? null : state.get("finalElementId");
        if (enabled && requested != null && source != null && count != null
                && id instanceof String) {
            Long token = handoff.acknowledge(requested.longValue(), source.longValue(),
                    (String) id, count.intValue());
            if (token != null) {
                Long retiring = token;
                InkHandoffCoordinator.deferClear(() -> {
                    if (handoff.confirmRetired(retiring) != null) clearToken(retiring);
                });
            }
        }
        completion.run();
    }

    @Override public void cancelRegisteredStroke(Object arguments) {
        Map<?, ?> state = map(arguments);
        Number requested = state == null ? null : number(state.get("generation"));
        Number source = state == null ? null : number(state.get("sourceTimestampUs"));
        if (requested == null || source == null) return;
        Long token = handoff.cancel(requested.longValue(), source.longValue());
        if (token != null) clearToken(token);
    }

    private void nativeFinished(long token, long callbackGeneration) {
        if (!enabled || failed || callbackGeneration != generation) return;
        Long removable = handoff.markNativeFinished(token);
        if (removable != null) {
            Long retiring = removable;
            InkHandoffCoordinator.deferClear(() -> {
                if (handoff.confirmRetired(retiring) != null) clearToken(retiring);
            });
        }
    }

    private void clearToken(long token) {
        Snapshot snapshot = latestSnapshot;
        if (snapshot == null || snapshot.token != token || clearRequested) {
            quarantine("clearToken: mismatch token=" + token + " latestSnapshotToken="
                    + (snapshot == null ? "null" : snapshot.token) + " clearRequested="
                    + clearRequested);
            return;
        }
        clearRequested = true;
        maybeIssueClear();
    }

    private void maybeIssueClear() {
        if (!clearRequested || pendingClear.get() != 0 || pendingFront.get() != 0
                || pendingMulti.get() != 0 || pendingTransactions.get() != 0
                || !frontCallbacks.isEmpty() || !multiCallbacks.isEmpty()) return;
        CanvasFrontBufferedRenderer<Request> currentRenderer = renderer;
        if (currentRenderer == null || failed || !enabled) {
            quarantine("maybeIssueClear: rendererNull=" + (currentRenderer == null)
                    + " failed=" + failed + " enabled=" + enabled);
            return;
        }
        pendingClear.incrementAndGet();
        try {
            currentRenderer.clear();
        } catch (RuntimeException | LinkageError error) {
            pendingClear.decrementAndGet();
            quarantine("clear() threw", error);
        }
    }

    private void clearCompleted() {
        pendingClear.set(0);
        clearRequested = false;
        latestSnapshot = null;
        activeToken = Long.MIN_VALUE;
        activeSourceTimestampUs = Long.MIN_VALUE;
        points.clear();
        drainedCount = 0;
        totalPointCount = 0;
        lastInputPoint = null;
    }

    @Override public void disable() {
        configured = false;
        enabled = false;
        ready = false;
        clearRequested = false;
        pendingSnapshot = null;
        drainPosted = false;
        frontRenderOutstanding = false;
        activePointer = -1;
        activeToken = Long.MIN_VALUE;
        activeSourceTimestampUs = Long.MIN_VALUE;
        points.clear();
        drainedCount = 0;
        totalPointCount = 0;
        lastInputPoint = null;
        pendingPredicted = null;
        predictor = null;
        platformPredictor = null;
        handoff.clear();
        resolveActivation(false);
        view.setVisibility(View.INVISIBLE);
        releaseRenderer();
        bounds = null;
        policy = null;
    }

    @Override public void destroy() { disable(); }

    private final class RendererCallback implements CanvasFrontBufferedRenderer.Callback<Request> {
        @Override public void onDrawFrontBufferedLayer(Canvas canvas, int width, int height,
                Request request) {
            if (request == null || request.kind != Kind.STROKE || request.snapshot == null) {
                postFailure("onDrawFrontBufferedLayer: invalid request kind="
                        + (request == null ? "null request" : request.kind));
                return;
            }
            boolean success = drawFrontTail(canvas, request.snapshot);
            frontCallbacks.add(new CallbackRecord(request.snapshot, Kind.STROKE,
                    request.generation, request.readinessEpoch, success));
            pendingFront.incrementAndGet();
        }

        @Override public void onDrawMultiBufferedLayer(Canvas canvas, int width, int height,
                Collection<? extends Request> requests) {
            if (requests == null || requests.isEmpty()) {
                canvas.drawColor(0, BlendMode.CLEAR);
                multiCallbacks.add(new CallbackRecord(null, Kind.ANDROIDX_CONTROL,
                        generation, readinessEpoch, true));
                return;
            }
            Request last = null;
            for (Request request : requests) last = request;
            if (last == null) {
                postFailure("onDrawMultiBufferedLayer: no last request in non-empty collection");
                return;
            }
            if (requests.size() == 1 && last.kind == Kind.PREFLIGHT) {
                canvas.drawColor(0, BlendMode.CLEAR);
                multiCallbacks.add(new CallbackRecord(null, Kind.PREFLIGHT,
                        last.generation, last.readinessEpoch, true));
                return;
            }
            if (last.kind != Kind.STROKE || last.snapshot == null) {
                postFailure("onDrawMultiBufferedLayer: last request kind=" + last.kind
                        + " snapshotNull=" + (last.snapshot == null));
                return;
            }
            boolean success = drawFullOutline(canvas, last.snapshot);
            multiCallbacks.add(new CallbackRecord(last.snapshot, Kind.STROKE,
                    last.generation, last.readinessEpoch, success));
        }

        @Override public void onFrontBufferedLayerRenderComplete(SurfaceControlCompat front,
                SurfaceControlCompat.Transaction transaction) {
            CallbackRecord record = frontCallbacks.poll();
            if (record == null) {
                // CanvasFrontBufferedRenderer primes its front-buffer SurfaceControl the same
                // way it primes the multi-buffered layer (see the empty-`requests` branch in
                // onDrawMultiBufferedLayer above): it can invoke this completion callback once,
                // with no prior onDrawFrontBufferedLayer call and hence nothing queued here, as
                // part of setting up the front buffer's transaction machinery. That is benign
                // exactly when we were not expecting a completion at all (frontRenderOutstanding
                // false, i.e. we have no renderFrontBufferedLayer() call in flight); only treat
                // an empty queue as a real failure if we WERE expecting one.
                if (frontRenderOutstanding) {
                    postFailure("onFrontBufferedLayerRenderComplete: frontCallbacks empty while "
                            + "a render was outstanding");
                } else {
                    Log.d(TAG, "onFrontBufferedLayerRenderComplete: ignoring renderer-internal "
                            + "front buffer priming completion (no render outstanding)");
                }
                return;
            }
            pendingFront.decrementAndGet();
            recordCommitted(transaction, record, false);
            view.post(() -> completeFrontRender(record));
        }

        @Override public void onMultiBufferedLayerRenderComplete(SurfaceControlCompat front,
                SurfaceControlCompat multi, SurfaceControlCompat.Transaction transaction) {
            CallbackRecord record = multiCallbacks.poll();
            if (record == null) {
                if (pendingClear.get() > 0) {
                    view.post(SharedGeometryInkOverlay.this::clearCompleted);
                } else {
                    postFailure("onMultiBufferedLayerRenderComplete: multiCallbacks empty and no "
                            + "pending clear");
                }
                return;
            }
            if (record.kind == Kind.PREFLIGHT || record.kind == Kind.STROKE) {
                pendingMulti.decrementAndGet();
            }
            if (record.kind == Kind.ANDROIDX_CONTROL) {
                view.post(() -> {
                    if (record.generation == generation && record.readinessEpoch == readinessEpoch) {
                        rendererControlReady = true;
                        maybeReady();
                    }
                });
            } else if (record.kind == Kind.PREFLIGHT) {
                view.post(() -> {
                    if (record.successful && record.generation == generation
                            && record.readinessEpoch == readinessEpoch && enabled && surfaceReady) {
                        ready = true;
                        resolveActivation(true);
                    }
                });
            } else {
                recordCommitted(transaction, record, true);
            }
            view.post(() -> {
                drainLatest();
                maybeIssueClear();
            });
        }

        private void recordCommitted(SurfaceControlCompat.Transaction transaction,
                CallbackRecord record, boolean multi) {
            Snapshot snapshot = record.snapshot;
            if (!record.successful || snapshot == null) {
                postFailure("recordCommitted: successful=" + record.successful + " snapshotNull="
                        + (snapshot == null) + " kind=" + record.kind + " multi=" + multi);
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                pendingTransactions.incrementAndGet();
                try {
                    transaction.addTransactionCommittedListener(DIRECT, () -> {
                        try {
                            if (multi && snapshot.complete) {
                                view.post(() -> nativeFinished(snapshot.token,
                                        snapshot.generation));
                            }
                        } finally {
                            pendingTransactions.decrementAndGet();
                            view.post(SharedGeometryInkOverlay.this::maybeIssueClear);
                        }
                    });
                    return;
                } catch (RuntimeException error) {
                    pendingTransactions.decrementAndGet();
                }
            }
            if (multi && snapshot.complete) {
                view.post(() -> nativeFinished(snapshot.token, snapshot.generation));
            }
        }
    }

    // engine/engineToken confinement to the renderer thread is documented on their declaration.
    private PerfectFreehandGeometry.IncrementalOutline engineFor(Snapshot snapshot) {
        if (engine == null || engineToken != snapshot.token) {
            engine = new PerfectFreehandGeometry.IncrementalOutline(snapshot.policy.width,
                    snapshot.policy.thinning, snapshot.policy.smoothing, snapshot.policy.streamline,
                    snapshot.policy.firstPressure);
            engineToken = snapshot.token;
        }
        return engine;
    }

    /**
     * Draws only the outline entries the stroke's {@link PerfectFreehandGeometry.IncrementalOutline}
     * added for {@code snapshot.newPoints} (plus, while the engine is still settling, an
     * occasional small from-scratch redraw -- see {@link PerfectFreehandGeometry.IncrementalOutline}),
     * never clearing once settled. A transient predicted tail, if any, is appended on top in
     * {@link #predictedPaint} (a lighter alpha than the real tail's opaque {@link #paint}) and is
     * never retained by the engine, so the next real frame's tail naturally overdraws most of it.
     * Any lighter 'shadow' pixels a direction change leaves beside the real stroke do not linger
     * past the next {@link #CHECKPOINT_POINTS}-point checkpoint or stroke completion: both call
     * {@link #drawFullOutline}, which clears the buffer and redraws only the engine's committed
     * (non-predicted) geometry, so no per-frame clear is needed to keep the residual bounded.
     */
    private boolean drawFrontTail(Canvas canvas, Snapshot snapshot) {
        try {
            PerfectFreehandGeometry.IncrementalOutline current = engineFor(snapshot);
            PerfectFreehandGeometry.IncrementalOutline.TailResult result =
                    current.appendPoints(snapshot.newPoints);
            if (result.clearFirst) canvas.drawColor(0, BlendMode.CLEAR);
            if (!result.polygon.isEmpty()) {
                PerfectFreehandGeometry.buildFilledQuadraticPath(result.polygon, scratchPath);
                canvas.drawPath(scratchPath, paint);
            }
            if (snapshot.predicted != null) {
                List<PerfectFreehandGeometry.Point> predictedTail =
                        current.predictTail(snapshot.predicted);
                if (!predictedTail.isEmpty()) {
                    PerfectFreehandGeometry.buildFilledQuadraticPath(predictedTail, scratchPath);
                    canvas.drawPath(scratchPath, predictedPaint);
                }
            }
            return true;
        } catch (RuntimeException | LinkageError error) {
            postFailure("drawFrontTail threw for token=" + snapshot.token, error);
            return false;
        }
    }

    /**
     * The multi-buffered (checkpoint/completion) draw: the exact full outline, from the same
     * engine's own accumulated state -- proven equal to a from-scratch computation by
     * {@code PerfectFreehandGeometryParityTest} -- rather than a separate recompute.
     */
    private boolean drawFullOutline(Canvas canvas, Snapshot snapshot) {
        try {
            canvas.drawColor(0, BlendMode.CLEAR);
            PerfectFreehandGeometry.IncrementalOutline current = engineFor(snapshot);
            PerfectFreehandGeometry.buildFilledQuadraticPath(
                    current.assembleFullOutline(), scratchPath);
            canvas.drawPath(scratchPath, paint);
            return true;
        } catch (RuntimeException | LinkageError error) {
            postFailure("drawFullOutline threw for token=" + snapshot.token, error);
            return false;
        }
    }

    private void prewarmGeometry() {
        Policy current = policy;
        if (current == null) return;
        try {
            List<PerfectFreehandGeometry.Point> warm = List.of(
                    new PerfectFreehandGeometry.Point(0, 0, .5),
                    new PerfectFreehandGeometry.Point(1, 1, .5));
            PerfectFreehandGeometry.buildFilledQuadraticPath(
                    PerfectFreehandGeometry.getStroke(warm,
                            PerfectFreehandGeometry.butterflyOptions(current.width,
                                    current.thinning, current.smoothing, current.streamline, true)));
            // A throwaway instance -- never the shared, renderer-thread-confined `engine` field --
            // just to JIT-warm the incremental path the same way the call above warms the static one.
            PerfectFreehandGeometry.IncrementalOutline warmEngine =
                    new PerfectFreehandGeometry.IncrementalOutline(
                            current.width, current.thinning, current.smoothing, current.streamline,
                            current.firstPressure);
            warmEngine.appendPoints(warm);
        } catch (RuntimeException | LinkageError error) {
            quarantine("prewarmGeometry threw", error);
        }
    }

    private void postFailure(String reason) { postFailure(reason, null); }

    private void postFailure(String reason, @Nullable Throwable error) {
        view.post(() -> quarantine(reason, error));
    }

    private void quarantine(String reason) { quarantine(reason, null); }

    private void quarantine(String reason, @Nullable Throwable error) {
        if (failed) return;
        if (error != null) {
            Log.w(TAG, "quarantine: " + reason, error);
        } else {
            Log.w(TAG, "quarantine: " + reason);
        }
        failed = true;
        configured = false;
        enabled = false;
        ready = false;
        handoff.clear();
        view.setVisibility(View.INVISIBLE);
        resolveActivation(false);
        releaseRenderer();
        FailureCallback callback = failureCallback;
        if (callback != null) {
            Log.w(TAG, "quarantine: invoking FailureCallback.onFailure generation=" + generation);
            callback.onFailure(generation);
        }
    }

    private void releaseRenderer() {
        if (surfaceCallbackRegistered) {
            view.getHolder().removeCallback(surfaceCallback);
            surfaceCallbackRegistered = false;
        }
        CanvasFrontBufferedRenderer<Request> current = renderer;
        renderer = null;
        frontCallbacks.clear();
        multiCallbacks.clear();
        frontRenderOutstanding = false;
        pendingFront.set(0);
        pendingMulti.set(0);
        pendingTransactions.set(0);
        pendingClear.set(0);
        if (current != null) {
            try { current.release(true); } catch (RuntimeException | LinkageError ignored) {}
        }
    }

    private boolean applyBounds() {
        RectF currentBounds = bounds;
        ViewGroup.LayoutParams layout = view.getLayoutParams();
        if (currentBounds == null || layout == null || currentBounds.isEmpty()) return false;
        layout.width = Math.round(currentBounds.width());
        layout.height = Math.round(currentBounds.height());
        if (layout.width <= 0 || layout.height <= 0) return false;
        if (layout instanceof ViewGroup.MarginLayoutParams margins) {
            margins.leftMargin = Math.round(currentBounds.left);
            margins.topMargin = Math.round(currentBounds.top);
            margins.rightMargin = 0;
            margins.bottomMargin = 0;
        } else {
            view.setX(currentBounds.left);
            view.setY(currentBounds.top);
        }
        view.setLayoutParams(layout);
        return true;
    }

    private boolean inCanvas(MotionEvent event, int index) {
        return inCanvas(event.getX(index), event.getY(index));
    }

    private boolean inCanvas(float x, float y) {
        return bounds != null && bounds.contains(x, y);
    }

    private static float normalizedPressure(float raw, float minimum, float maximum) {
        float span = maximum - minimum;
        // raw, minimum and maximum are already guaranteed finite here: eligibleSample()
        // validates every sample's pressure and the device's min/max -- using this same event,
        // so the same range -- before appendEvent ever runs. This branch is therefore
        // unreachable in practice; left as a defensive mirror of Dart's getPressureOfEvent,
        // which folds an unexpected NaN into 0.5 rather than propagating it.
        if (!Float.isFinite(raw) || !Float.isFinite(span)) return Float.NaN;
        if (span <= 0f) span = 1f;
        float pressure = (raw - minimum) / span;
        return !Float.isFinite(pressure) || pressure <= 0f ? .5f : pressure;
    }

    private static boolean eligibleSample(MotionEvent event, int index) {
        if (index < 0 || index >= event.getPointerCount()
                || event.getToolType(index) != MotionEvent.TOOL_TYPE_STYLUS
                || event.getButtonState() != 0
                || (event.getMetaState() & (KeyEvent.META_SHIFT_MASK | KeyEvent.META_ALT_MASK
                | KeyEvent.META_CTRL_MASK)) != 0) return false;
        InputDevice device = event.getDevice();
        InputDevice.MotionRange range = device == null ? null
                : device.getMotionRange(MotionEvent.AXIS_PRESSURE);
        float minimum = range == null ? 0f : range.getMin();
        float maximum = range == null ? 1f : range.getMax();
        if (!Float.isFinite(minimum) || !Float.isFinite(maximum) || minimum > maximum) return false;
        float current = event.getPressure(index);
        if (!Float.isFinite(current) || current < minimum || current > maximum) return false;
        for (int history = 0; history < event.getHistorySize(); history++) {
            float historical = event.getHistoricalPressure(index, history);
            if (!Float.isFinite(historical) || historical < minimum || historical > maximum) {
                return false;
            }
        }
        return true;
    }

    @Nullable private static RectF parseBounds(@Nullable Map<?, ?> values, float dpr) {
        if (values == null || !Float.isFinite(dpr) || dpr <= 0f) return null;
        Number left = number(values.get("left"));
        Number top = number(values.get("top"));
        Number right = number(values.get("right"));
        Number bottom = number(values.get("bottom"));
        if (left == null || top == null || right == null || bottom == null) return null;
        RectF result = new RectF(left.floatValue() * dpr, top.floatValue() * dpr,
                right.floatValue() * dpr, bottom.floatValue() * dpr);
        return Float.isFinite(result.left) && Float.isFinite(result.top)
                && Float.isFinite(result.right) && Float.isFinite(result.bottom)
                && !result.isEmpty() ? result : null;
    }

    @Nullable private static Policy parsePolicy(@Nullable Map<?, ?> values, float dpr) {
        if (values == null || !Float.isFinite(dpr) || dpr <= 0f) return null;
        Number argb = number(values.get("argb"));
        Number width = number(values.get("width"));
        Number thinning = number(values.get("thinning"));
        Number smoothing = number(values.get("smoothing"));
        Number streamline = number(values.get("streamline"));
        Object pressure = values.get("pressurePolicy");
        if (argb == null || width == null || thinning == null || smoothing == null
                || streamline == null || !(pressure instanceof String)
                || (argb.intValue() >>> 24) != 0xFF) return null;
        float physicalWidth = width.floatValue() * dpr;
        float thin = thinning.floatValue();
        float smooth = smoothing.floatValue();
        float stream = streamline.floatValue();
        if (!Float.isFinite(physicalWidth) || physicalWidth <= 0f || !Float.isFinite(thin)
                || !Float.isFinite(smooth) || !Float.isFinite(stream)
                || (!("never".equals(pressure)) && !("first".equals(pressure)))) return null;
        return new Policy(argb.intValue(), physicalWidth,
                Math.max(0f, Math.min(1f, thin)), Math.max(0f, Math.min(1f, smooth)),
                Math.max(.1f, Math.min(1f, stream)), "first".equals(pressure));
    }

    @Nullable private static Map<?, ?> map(Object value) {
        return value instanceof Map ? (Map<?, ?>) value : null;
    }
    @Nullable private static Number number(Object value) {
        return value instanceof Number ? (Number) value : null;
    }
    private static boolean bool(Object value) {
        return value instanceof Boolean && (Boolean) value;
    }
}
