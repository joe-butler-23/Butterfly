package dev.linwood.butterfly;

import android.content.Context;
import android.graphics.RectF;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.ink.authoring.InProgressStrokeId;
import androidx.ink.authoring.InProgressStrokesFinishedListener;
import androidx.ink.authoring.InProgressStrokesView;
import androidx.ink.brush.Brush;
import androidx.ink.brush.StockBrushes;
import androidx.ink.strokes.Stroke;
import androidx.input.motionprediction.MotionEventPredictor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** AndroidX Ink draws transient wet ink while Flutter receives every original event. */
final class InkLatencyOverlay implements StylusWetInkRenderer {
    private final InProgressStrokesView view;
    private final View flutterInputView;
    private final boolean predictionArm;
    private final Map<Integer, InProgressStrokeId> active = new HashMap<>();
    private final InkHandoffCoordinator<InProgressStrokeId> handoff =
            new InkHandoffCoordinator<>();
    private final int[] location = new int[2];
    private final InProgressStrokesFinishedListener finishedListener;

    private Brush brush = createBrush(0xFF000000, 5f);
    private RectF canvasBounds;
    private MotionEventPredictor predictor;
    private long generation = Long.MIN_VALUE;
    private boolean configured;
    private boolean enabled;
    private boolean failed;
    private boolean eagerInitRequested;

    InkLatencyOverlay(Context context, View flutterInputView, boolean predictionArm) {
        this.flutterInputView = flutterInputView;
        this.predictionArm = predictionArm;
        view = new InProgressStrokesView(context);
        view.setClickable(false);
        view.setFocusable(false);
        view.setClipChildren(true);
        view.setClipToPadding(true);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        finishedListener = new InProgressStrokesFinishedListener() {
            @Override
            public void onStrokesFinished(Map<InProgressStrokeId, Stroke> strokes) {
                for (InProgressStrokeId stroke : strokes.keySet()) {
                    InProgressStrokeId removable = handoff.markNativeFinished(stroke);
                    if (removable != null) removeFinished(removable);
                }
                removeEvicted();
            }
        };
        view.addFinishedStrokesListener(finishedListener);
    }

    @Override public View getView() { return view; }

    @Override public boolean configure(Object arguments) {
        if (failed) return false;
        disable();
        Map<?, ?> state = map(arguments);
        Number version = state == null ? null : number(state.get("protocolVersion"));
        Number nextGeneration = state == null ? null : number(state.get("generation"));
        Number argb = state == null ? null : number(state.get("argb"));
        Number width = state == null ? null : number(state.get("width"));
        Number dpr = state == null ? null : number(state.get("devicePixelRatio"));
        Map<?, ?> bounds = state == null ? null : map(state.get("canvasBounds"));
        if (version == null || version.intValue() != 1 || nextGeneration == null
                || argb == null || width == null || dpr == null || bounds == null
                || !bool(state.get("enabled")) || !bool(state.get("eligible"))) return false;
        float ratio = dpr.floatValue();
        float physicalWidth = width.floatValue() * ratio;
        RectF parsedBounds = parseBounds(bounds, ratio);
        if (!Float.isFinite(physicalWidth) || physicalWidth <= 0f
                || (argb.intValue() >>> 24) != 0xFF || parsedBounds == null) return false;
        generation = nextGeneration.longValue();
        canvasBounds = parsedBounds;
        brush = createBrush(argb.intValue(), physicalWidth);
        handoff.setGeneration(generation);
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
        try {
            if (!eagerInitRequested) {
                view.eagerInit();
                eagerInitRequested = true;
            }
            if (predictionArm && predictor == null) {
                predictor = MotionEventPredictor.newInstance(view);
            }
            view.setVisibility(View.VISIBLE);
            return true;
        } catch (RuntimeException | LinkageError error) {
            quarantine();
            return false;
        }
    }

    @Override public void awaitActivation(ActivationCallback callback) {
        callback.onActivated(enabled && configured && eagerInitRequested && !failed);
    }

    @Override public void onMotionEvent(@NonNull MotionEvent source) {
        if (!shouldStartOrContinue(source)) return;
        MotionEvent event = null;
        MotionEvent prediction = null;
        try {
            event = MotionEvent.obtain(source);
            view.getLocationInWindow(location);
            event.offsetLocation(-location[0], -location[1]);
            int action = event.getActionMasked();
            int index = event.getActionIndex();
            if (action == MotionEvent.ACTION_CANCEL
                    || (event.getFlags() & MotionEvent.FLAG_CANCELED) != 0
                    || event.getPointerCount() != 1 || index != 0
                    || !eligibleSample(event, index)) {
                cancelActiveStroke();
                return;
            }
            if (predictionArm) {
                if (predictor == null) predictor = MotionEventPredictor.newInstance(view);
                predictor.record(event);
                if (action == MotionEvent.ACTION_MOVE) prediction = predictor.predict();
            }
            switch (action) {
                case MotionEvent.ACTION_DOWN -> start(event, source);
                case MotionEvent.ACTION_MOVE -> append(event, prediction);
                case MotionEvent.ACTION_UP -> finish(event);
                default -> cancelActiveStroke();
            }
        } catch (RuntimeException | LinkageError error) {
            quarantine();
        } finally {
            if (prediction != null) prediction.recycle();
            if (event != null) event.recycle();
        }
    }

    private boolean shouldStartOrContinue(MotionEvent event) {
        if (!enabled || failed) return false;
        if (!active.isEmpty()) return true;
        int index = event.getActionIndex();
        return event.getActionMasked() == MotionEvent.ACTION_DOWN
                && event.getPointerCount() == 1 && index == 0
                && eligibleSample(event, index) && inCanvas(event, index);
    }

    private void start(MotionEvent event, MotionEvent original) {
        if (!active.isEmpty()) {
            cancelActiveStroke();
            return;
        }
        if (handoff.retainedCount() != 0 || !inOverlay(event, 0)) return;
        int pointer = event.getPointerId(0);
        InProgressStrokeId stroke = view.startStroke(event, pointer, brush);
        active.put(pointer, stroke);
        handoff.addNativeStroke(generation, event.getEventTime() * 1_000L, stroke);
        flutterInputView.requestUnbufferedDispatch(original);
        removeEvicted();
    }

    private void append(MotionEvent event, MotionEvent prediction) {
        if (active.size() != 1) {
            cancelActiveStroke();
            return;
        }
        Map.Entry<Integer, InProgressStrokeId> entry = active.entrySet().iterator().next();
        int pointer = entry.getKey();
        int index = event.findPointerIndex(pointer);
        if (index != 0 || !inOverlay(event, index)) {
            cancelActiveStroke();
            return;
        }
        view.addToStroke(event, pointer, entry.getValue(), prediction);
    }

    private void finish(MotionEvent event) {
        if (active.size() != 1) {
            cancelActiveStroke();
            return;
        }
        Map.Entry<Integer, InProgressStrokeId> entry = active.entrySet().iterator().next();
        int pointer = entry.getKey();
        int index = event.findPointerIndex(pointer);
        if (index != 0 || event.getPointerId(event.getActionIndex()) != pointer
                || !inOverlay(event, index)) {
            cancelActiveStroke();
            return;
        }
        try {
            view.finishStroke(event, pointer, entry.getValue());
        } catch (RuntimeException | LinkageError error) {
            cancelStroke(entry.getValue(), event);
            handoff.cancelNative(entry.getValue());
            quarantine();
        } finally {
            active.remove(pointer);
            predictor = null;
        }
    }

    private void cancelActiveStroke() {
        if (active.isEmpty()) {
            predictor = null;
            return;
        }
        for (InProgressStrokeId stroke : active.values()) {
            cancelStroke(stroke, null);
            handoff.cancelNative(stroke);
        }
        active.clear();
        predictor = null;
        removeEvicted();
    }

    @Override public void registerStroke(Object arguments) {
        Map<?, ?> state = map(arguments);
        Number requested = state == null ? null : number(state.get("generation"));
        Number sequence = state == null ? null : number(state.get("strokeSequence"));
        Number source = state == null ? null : number(state.get("sourceTimestampUs"));
        if (!enabled || requested == null || sequence == null || source == null
                || requested.longValue() != generation) return;
        handoff.register(new InkHandoffCoordinator.Registration(
                requested.longValue(), sequence.longValue(), source.longValue()));
        removeEvicted();
    }

    @Override public void acknowledgeStroke(Object arguments, Runnable completion) {
        try {
            Map<?, ?> state = map(arguments);
            Number requested = state == null ? null : number(state.get("generation"));
            Number sequence = state == null ? null : number(state.get("strokeSequence"));
            Number source = state == null ? null : number(state.get("sourceTimestampUs"));
            Number count = state == null ? null : number(state.get("finalPointCount"));
            Object id = state == null ? null : state.get("finalElementId");
            if (!enabled || requested == null || sequence == null || source == null
                    || count == null || !(id instanceof String)) return;
            InProgressStrokeId stroke = handoff.acknowledge(requested.longValue(),
                    sequence.longValue(), source.longValue(), (String) id, count.intValue());
            if (stroke != null) removeFinished(stroke);
            removeEvicted();
        } finally {
            completion.run();
        }
    }

    @Override public void cancelRegisteredStroke(Object arguments) {
        Map<?, ?> state = map(arguments);
        Number requested = state == null ? null : number(state.get("generation"));
        Number sequence = state == null ? null : number(state.get("strokeSequence"));
        Number source = state == null ? null : number(state.get("sourceTimestampUs"));
        if (requested == null || sequence == null || source == null) return;
        InProgressStrokeId stroke = handoff.cancel(requested.longValue(), sequence.longValue(),
                source.longValue());
        if (stroke != null) {
            cancelStroke(stroke, null);
            removeFinished(stroke);
        }
        removeEvicted();
    }

    @Override public void disable() {
        enabled = false;
        configured = false;
        canvasBounds = null;
        predictor = null;
        cancelAll();
        removeAllFinished();
        handoff.clear();
        view.setVisibility(View.INVISIBLE);
    }

    @Override public void destroy() {
        disable();
        view.removeFinishedStrokesListener(finishedListener);
    }

    private void removeEvicted() {
        List<InProgressStrokeId> strokes = handoff.drainEvictedTokens();
        for (InProgressStrokeId stroke : strokes) {
            cancelStroke(stroke, null);
            removeFinished(stroke);
        }
        if (handoff.isQuarantined()) quarantine();
    }

    private void quarantine() {
        if (failed) return;
        failed = true;
        enabled = false;
        configured = false;
        predictor = null;
        cancelAll();
        removeAllFinished();
        handoff.clear();
        view.setVisibility(View.INVISIBLE);
    }

    private void cancelAll() {
        for (InProgressStrokeId stroke : active.values()) {
            cancelStroke(stroke, null);
            handoff.cancelNative(stroke);
        }
        active.clear();
    }

    private void cancelStroke(InProgressStrokeId stroke, MotionEvent event) {
        try { view.cancelStroke(stroke, event); } catch (RuntimeException | LinkageError ignored) {}
    }

    private void removeFinished(InProgressStrokeId stroke) {
        try {
            view.removeFinishedStrokes(java.util.Collections.singleton(stroke));
        } catch (RuntimeException | LinkageError error) {
            quarantine();
        }
    }

    private void removeAllFinished() {
        try { view.removeFinishedStrokes(view.getFinishedStrokes().keySet()); }
        catch (RuntimeException | LinkageError ignored) {}
    }

    private boolean applyBounds() {
        RectF bounds = canvasBounds;
        ViewGroup.LayoutParams parameters = view.getLayoutParams();
        if (bounds == null || parameters == null || bounds.isEmpty()) return false;
        parameters.width = Math.round(bounds.width());
        parameters.height = Math.round(bounds.height());
        if (parameters.width <= 0 || parameters.height <= 0) return false;
        if (parameters instanceof ViewGroup.MarginLayoutParams margins) {
            margins.leftMargin = Math.round(bounds.left);
            margins.topMargin = Math.round(bounds.top);
            margins.rightMargin = 0;
            margins.bottomMargin = 0;
        } else {
            view.setX(bounds.left);
            view.setY(bounds.top);
        }
        view.setLayoutParams(parameters);
        return true;
    }

    private boolean inCanvas(MotionEvent event, int index) {
        return canvasBounds != null && canvasBounds.contains(event.getX(index), event.getY(index));
    }

    private boolean inOverlay(MotionEvent event, int index) {
        return event.getX(index) >= 0 && event.getY(index) >= 0
                && event.getX(index) < view.getWidth() && event.getY(index) < view.getHeight();
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
        float current = event.getPressure(index);
        if (!Float.isFinite(minimum) || !Float.isFinite(maximum) || minimum > maximum
                || !Float.isFinite(current) || current < minimum || current > maximum) return false;
        for (int history = 0; history < event.getHistorySize(); history++) {
            float pressure = event.getHistoricalPressure(index, history);
            if (!Float.isFinite(pressure) || pressure < minimum || pressure > maximum) return false;
        }
        return true;
    }

    private static Brush createBrush(int argb, float width) {
        return Brush.createWithColorIntArgb(StockBrushes.pressurePen(), argb, width,
                Math.min(0.1f, width));
    }

    private static RectF parseBounds(Map<?, ?> bounds, float dpr) {
        Number left = number(bounds.get("left"));
        Number top = number(bounds.get("top"));
        Number right = number(bounds.get("right"));
        Number bottom = number(bounds.get("bottom"));
        if (left == null || top == null || right == null || bottom == null
                || !Float.isFinite(dpr) || dpr <= 0f) return null;
        RectF result = new RectF(left.floatValue() * dpr, top.floatValue() * dpr,
                right.floatValue() * dpr, bottom.floatValue() * dpr);
        return Float.isFinite(result.left) && Float.isFinite(result.top)
                && Float.isFinite(result.right) && Float.isFinite(result.bottom)
                && !result.isEmpty() ? result : null;
    }

    private static Map<?, ?> map(Object value) {
        return value instanceof Map ? (Map<?, ?>) value : null;
    }
    private static Number number(Object value) {
        return value instanceof Number ? (Number) value : null;
    }
    private static boolean bool(Object value) {
        return value instanceof Boolean && (Boolean) value;
    }
}
