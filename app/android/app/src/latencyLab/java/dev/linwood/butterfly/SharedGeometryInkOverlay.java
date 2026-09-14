package dev.linwood.butterfly;

import android.content.Context;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Build;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.graphics.lowlatency.CanvasFrontBufferedRenderer;
import androidx.graphics.surface.SurfaceControlCompat;

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
    private static final int MAX_POINTS = 512;
    private static final int CHECKPOINT_POINTS = 16;
    private static final Executor DIRECT = Runnable::run;

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

    private static final class Snapshot {
        final long token, generation;
        final List<PerfectFreehandGeometry.Point> points;
        final Policy policy;
        final boolean complete, checkpoint;

        Snapshot(long token, long generation, List<PerfectFreehandGeometry.Point> points,
                Policy policy, boolean complete, boolean checkpoint) {
            this.token = token;
            this.generation = generation;
            this.points = Collections.unmodifiableList(new ArrayList<>(points));
            this.policy = policy;
            this.complete = complete;
            this.checkpoint = checkpoint;
        }
    }

    private static final class PendingStroke {
        final long token, generation;
        final List<PerfectFreehandGeometry.Point> points;
        final Policy policy;
        boolean complete, checkpoint;

        PendingStroke(long token, long generation, List<PerfectFreehandGeometry.Point> points,
                Policy policy, boolean complete, boolean checkpoint) {
            this.token = token;
            this.generation = generation;
            this.points = points;
            this.policy = policy;
            this.complete = complete;
            this.checkpoint = checkpoint;
        }

        Snapshot snapshot() {
            return new Snapshot(token, generation, points, policy, complete, checkpoint);
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

    @Nullable private CanvasFrontBufferedRenderer<Request> renderer;
    @Nullable private RectF bounds;
    @Nullable private Policy policy;
    @Nullable private PendingStroke pendingSnapshot;
    @Nullable private Snapshot latestSnapshot;
    @Nullable private ActivationCallback activationCallback;
    @Nullable private FailureCallback failureCallback;
    private boolean drainPosted, frontRenderOutstanding;
    private boolean configured, enabled, attached, surfaceReady, rendererControlReady, ready;
    private boolean surfaceCallbackRegistered, failed, clearRequested;
    private long generation = Long.MIN_VALUE;
    private long readinessEpoch, preflightEpoch = Long.MIN_VALUE, prewarmedGeneration = Long.MIN_VALUE;
    private long nextToken, activeToken = Long.MIN_VALUE;
    private int activePointer = -1;

    SharedGeometryInkOverlay(Context context, View flutterInputView) {
        this.flutterInputView = flutterInputView;
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
                if (enabled) quarantine();
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
            quarantine();
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
            quarantine();
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
            if (active) abortStroke();
            return;
        }
        view.getLocationInWindow(location);
        float locationX = location[0];
        float locationY = location[1];
        int pointer = event.getPointerId(index);
        switch (action) {
            case MotionEvent.ACTION_DOWN -> begin(event, pointer, locationX, locationY);
            case MotionEvent.ACTION_MOVE -> {
                if (pointer != activePointer || !inCanvas(event, index)) abortStroke();
                else if (!appendEvent(event, index, false, locationX, locationY)) abortStroke();
            }
            case MotionEvent.ACTION_UP -> {
                if (pointer != activePointer || !inCanvas(event, index)) {
                    abortStroke();
                } else if (appendEvent(event, index, true, locationX, locationY)) {
                    activePointer = -1;
                } else {
                    abortStroke();
                }
            }
            default -> {
                if (active) abortStroke();
            }
        }
    }

    private void begin(MotionEvent event, int pointer, float locationX, float locationY) {
        if (activePointer != -1) {
            abortStroke();
            return;
        }
        if (handoff.retainedCount() != 0 || !inCanvas(event, 0)) {
            return;
        }
        activePointer = pointer;
        activeToken = ++nextToken;
        points.clear();
        // retainedCount() == 0 was just checked above, so this insert can never overflow
        // the bounded map; there is no per-token buffer here to clear if it somehow did.
        handoff.addNativeStroke(generation, event.getEventTime() * 1_000L, activeToken);
        flutterInputView.requestUnbufferedDispatch(event);
        if (!appendEvent(event, 0, false, locationX, locationY)) abortStroke();
    }

    private boolean appendEvent(MotionEvent event, int pointerIndex, boolean complete,
            float locationX, float locationY) {
        int initialPointCount = points.size();
        for (int history = 0; history < event.getHistorySize(); history++) {
            if (!appendPoint(event.getHistoricalX(pointerIndex, history),
                    event.getHistoricalY(pointerIndex, history),
                    normalizedPressure(event.getHistoricalPressure(pointerIndex, history), event),
                    locationX, locationY)) return false;
        }
        if (!appendPoint(event.getX(pointerIndex), event.getY(pointerIndex),
                normalizedPressure(event.getPressure(pointerIndex), event), locationX, locationY)) {
            return false;
        }
        if (points.size() < 2) return !complete;
        boolean changed = points.size() != initialPointCount;
        if (changed || complete) {
            submit(complete, !complete && points.size() % CHECKPOINT_POINTS == 0);
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
        if (!points.isEmpty()) {
            PerfectFreehandGeometry.Point last = points.get(points.size() - 1);
            if (last.x == x && last.y == y) return true;
        }
        if (points.size() == MAX_POINTS) return false;
        if (policy != null && policy.firstPressure && points.size() == 1) {
            PerfectFreehandGeometry.Point first = points.get(0);
            points.set(0, new PerfectFreehandGeometry.Point(first.x, first.y,
                    (double) pressure));
        }
        points.add(new PerfectFreehandGeometry.Point(x, y, (double) pressure));
        return true;
    }

    private void submit(boolean complete, boolean checkpoint) {
        Policy currentPolicy = policy;
        if (currentPolicy == null || activeToken == Long.MIN_VALUE) {
            quarantine();
            return;
        }
        PendingStroke pending = pendingSnapshot;
        if (pending != null && pending.token == activeToken
                && pending.generation == generation) {
            pending.complete |= complete;
            pending.checkpoint |= checkpoint;
        } else {
            pendingSnapshot = new PendingStroke(activeToken, generation, points, currentPolicy,
                    complete, checkpoint);
        }
        if (frontRenderOutstanding || drainPosted || pendingFront.get() != 0
                || pendingMulti.get() != 0) return;
        drainPosted = true;
        // The first submission is already on the UI thread. Later arrivals are
        // coalesced at the view boundary while the renderer is busy.
        if (latestSnapshot == null) {
            drainLatest();
        } else {
            view.post(this::drainLatest);
        }
    }

    private void drainLatest() {
        if (frontRenderOutstanding || pendingFront.get() != 0 || pendingMulti.get() != 0) {
            return;
        }
        drainPosted = false;
        PendingStroke pending = pendingSnapshot;
        pendingSnapshot = null;
        Snapshot snapshot = pending == null ? null : pending.snapshot();
        CanvasFrontBufferedRenderer<Request> currentRenderer = renderer;
        if (snapshot == null || currentRenderer == null || failed || !enabled
                || snapshot.generation != generation) return;
        latestSnapshot = snapshot;
        frontRenderOutstanding = true;
        try {
            currentRenderer.renderFrontBufferedLayer(Request.stroke(snapshot));
        } catch (RuntimeException | LinkageError error) {
            frontRenderOutstanding = false;
            quarantine();
        }
    }

    private void completeFrontRender(CallbackRecord record) {
        Snapshot snapshot = record.snapshot;
        if (snapshot == null || !record.successful || failed || !enabled
                || snapshot.generation != generation) {
            frontRenderOutstanding = false;
            return;
        }
        if (snapshot.complete || snapshot.checkpoint) {
            CanvasFrontBufferedRenderer<Request> currentRenderer = renderer;
            if (currentRenderer == null) {
                frontRenderOutstanding = false;
                quarantine();
                return;
            }
            pendingMulti.incrementAndGet();
            try {
                currentRenderer.commit();
            } catch (RuntimeException | LinkageError error) {
                pendingMulti.decrementAndGet();
                frontRenderOutstanding = false;
                quarantine();
                return;
            }
        }
        frontRenderOutstanding = false;
        drainLatest();
        maybeIssueClear();
    }

    private void abortStroke() {
        long token = activeToken;
        activePointer = -1;
        activeToken = Long.MIN_VALUE;
        points.clear();
        pendingSnapshot = null;
        if (token == Long.MIN_VALUE) return;
        handoff.cancelNative(token);
        Snapshot snapshot = latestSnapshot;
        if (snapshot != null && snapshot.token == token) {
            clearRequested = true;
            maybeIssueClear();
        }
    }

    @Override public void registerStroke(Object arguments) {
        Map<?, ?> state = map(arguments);
        Number requested = state == null ? null : number(state.get("generation"));
        Number source = state == null ? null : number(state.get("sourceTimestampUs"));
        if (requested == null || source == null || requested.longValue() != generation) return;
        if (!enabled || clearRequested || pendingClear.get() != 0) {
            handoff.cancel(requested.longValue(), source.longValue());
            return;
        }
        handoff.register(requested.longValue(), source.longValue());
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
            quarantine();
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
            quarantine();
            return;
        }
        pendingClear.incrementAndGet();
        try {
            currentRenderer.clear();
        } catch (RuntimeException | LinkageError error) {
            pendingClear.decrementAndGet();
            quarantine();
        }
    }

    private void clearCompleted() {
        pendingClear.set(0);
        clearRequested = false;
        latestSnapshot = null;
        activeToken = Long.MIN_VALUE;
        points.clear();
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
        points.clear();
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
                postFailure();
                return;
            }
            boolean success = drawSnapshot(canvas, request.snapshot);
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
                postFailure();
                return;
            }
            if (requests.size() == 1 && last.kind == Kind.PREFLIGHT) {
                canvas.drawColor(0, BlendMode.CLEAR);
                multiCallbacks.add(new CallbackRecord(null, Kind.PREFLIGHT,
                        last.generation, last.readinessEpoch, true));
                return;
            }
            if (last.kind != Kind.STROKE || last.snapshot == null) {
                postFailure();
                return;
            }
            boolean success = drawSnapshot(canvas, last.snapshot);
            multiCallbacks.add(new CallbackRecord(last.snapshot, Kind.STROKE,
                    last.generation, last.readinessEpoch, success));
        }

        @Override public void onFrontBufferedLayerRenderComplete(SurfaceControlCompat front,
                SurfaceControlCompat.Transaction transaction) {
            CallbackRecord record = frontCallbacks.poll();
            if (record == null) {
                postFailure();
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
                    postFailure();
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
                postFailure();
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

    private boolean drawSnapshot(Canvas canvas, Snapshot snapshot) {
        try {
            canvas.drawColor(0, BlendMode.CLEAR);
            boolean simulate = snapshot.points.size() < 2;
            if (!simulate) {
                simulate = true;
                double pressure = snapshot.points.get(1).pressure;
                for (int i = 2; i < snapshot.points.size(); i++) {
                    if (snapshot.points.get(i).pressure != pressure) {
                        simulate = false;
                        break;
                    }
                }
            }
            Path path = PerfectFreehandGeometry.buildFilledQuadraticPath(
                    PerfectFreehandGeometry.getStroke(snapshot.points,
                            PerfectFreehandGeometry.butterflyOptions(snapshot.policy.width,
                                    snapshot.policy.thinning, snapshot.policy.smoothing,
                                    snapshot.policy.streamline, simulate)));
            canvas.drawPath(path, paint);
            return true;
        } catch (RuntimeException | LinkageError error) {
            postFailure();
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
        } catch (RuntimeException | LinkageError error) {
            quarantine();
        }
    }

    private void postFailure() { view.post(this::quarantine); }

    private void quarantine() {
        if (failed) return;
        failed = true;
        configured = false;
        enabled = false;
        ready = false;
        handoff.clear();
        view.setVisibility(View.INVISIBLE);
        resolveActivation(false);
        releaseRenderer();
        FailureCallback callback = failureCallback;
        if (callback != null) callback.onFailure(generation);
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
        return bounds != null && bounds.contains(event.getX(index), event.getY(index));
    }

    private static float normalizedPressure(float raw, MotionEvent event) {
        InputDevice device = event.getDevice();
        InputDevice.MotionRange range = device == null ? null
                : device.getMotionRange(MotionEvent.AXIS_PRESSURE);
        float minimum = range == null ? 0f : range.getMin();
        float maximum = range == null ? 1f : range.getMax();
        float span = maximum - minimum;
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
