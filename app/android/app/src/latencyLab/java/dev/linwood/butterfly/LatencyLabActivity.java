package dev.linwood.butterfly;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Map;

import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.plugin.common.MethodChannel;

/** Isolated Activity used only by the latencyLab flavor. */
public final class LatencyLabActivity extends MainActivity {
    private static final String TAG = "ButterflyInk";
    private static final String CHANNEL = "linwood.dev/butterfly/ink";

    private static final String PREDICTION_MS_EXTRA = "dev.linwood.butterfly.extra.PREDICTION_MS";
    private static final int DEFAULT_PREDICTION_MS = 8;

    private StylusLatencyMode.Value mode = StylusLatencyMode.DEFAULT;
    private int predictionMs = DEFAULT_PREDICTION_MS;
    @Nullable private StylusWetInkRenderer wetInk;
    private long configuredGeneration = Long.MIN_VALUE;
    private boolean resumed, focused, multiWindow, pictureInPicture;
    @Nullable private MethodChannel channel;
    private boolean requestedSignalShown;

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        mode = resolveMode(getIntent());
        predictionMs = resolvePredictionMs(getIntent());
        super.onCreate(savedInstanceState);
        showRequestedSignal();
        if (mode == StylusLatencyMode.Value.FLUTTER_ONLY) showActiveSignal();
        multiWindow = isInMultiWindowMode();
        pictureInPicture = isInPictureInPictureMode();
    }

    @Override public void configureFlutterEngine(@NonNull FlutterEngine engine) {
        super.configureFlutterEngine(engine);
        channel = new MethodChannel(engine.getDartExecutor().getBinaryMessenger(), CHANNEL);
        channel.setMethodCallHandler((call, result) -> {
                    Log.d(TAG, "channel: " + call.method + " mode=" + mode + " resumed=" + resumed);
                    switch (call.method) {
                        case "configure" -> {
                            boolean configured = configureWetInk(call.arguments);
                            // Flutter-only has nothing to fall back from, and a configure
                            // that arrives before onResume is retried by Dart, not a failure.
                            if (!configured && resumed
                                    && mode != StylusLatencyMode.Value.FLUTTER_ONLY) {
                                Log.w(TAG, "configure: configureWetInk() returned false, mode="
                                        + mode);
                                showFallbackSignal();
                            }
                            result.success(configured);
                        }
                        case "enable" -> enableWetInk(call.arguments, result);
                        case "disable" -> {
                            if (matchesConfiguredGeneration(call.arguments)) destroyWetInk();
                            result.success(null);
                        }
                        case "registerStroke" -> {
                            if (wetInk != null) wetInk.registerStroke(call.arguments);
                            result.success(null);
                        }
                        case "acknowledgeStroke" -> {
                            StylusWetInkRenderer renderer = wetInk;
                            if (renderer == null) result.success(null);
                            else renderer.acknowledgeStroke(call.arguments,
                                    () -> result.success(null));
                        }
                        case "cancelStroke" -> {
                            if (wetInk != null) wetInk.cancelRegisteredStroke(call.arguments);
                            result.success(null);
                        }
                        default -> result.notImplemented();
                    }
                });
    }

    private boolean configureWetInk(Object arguments) {
        try {
            destroyWetInk();
            if (mode == StylusLatencyMode.Value.FLUTTER_ONLY || !resumed) return false;
            View flutterView = flutterView();
            if (flutterView == null) return false;
            wetInk = switch (mode) {
                case SHARED_GEOMETRY -> new SharedGeometryInkOverlay(this, flutterView,
                        predictionMs);
                default -> null;
            };
            StylusWetInkRenderer renderer = wetInk;
            if (renderer == null) return false;
            renderer.setFailureCallback(
                    generation -> onRendererFailure(renderer, generation));
            if (!renderer.configure(arguments)) {
                Log.w(TAG, "configureWetInk: renderer.configure() returned false, mode=" + mode);
                destroyWetInk();
                return false;
            }
            configuredGeneration = generation(arguments);
            if (configuredGeneration == Long.MIN_VALUE) {
                Log.w(TAG, "configureWetInk: arguments carried no usable generation");
            }
            return configuredGeneration != Long.MIN_VALUE;
        } catch (RuntimeException | LinkageError error) {
            Log.w(TAG, "configureWetInk threw", error);
            destroyWetInk();
            return false;
        }
    }

    private void enableWetInk(Object arguments, MethodChannel.Result result) {
        try {
            StylusWetInkRenderer renderer = wetInk;
            if (renderer == null || !resumed || !matchesConfiguredGeneration(arguments)
                    || !renderer.enable(arguments) || !attach(renderer)) {
                Log.w(TAG, "enableWetInk: preconditions failed, rendererNull="
                        + (renderer == null) + " resumed=" + resumed + " generationMatch="
                        + matchesConfiguredGeneration(arguments));
                destroyWetInk();
                result.success(false);
                showFallbackSignal();
                return;
            }
            if (!renderer.attachAndPrewarm()) {
                boolean failureAlreadySignaled = renderer != wetInk;
                Log.w(TAG, "enableWetInk: attachAndPrewarm() returned false, "
                        + "failureAlreadySignaled=" + failureAlreadySignaled);
                destroyWetInk();
                result.success(false);
                if (!failureAlreadySignaled) showFallbackSignal();
                return;
            }
            renderer.awaitActivation(enabled -> {
                boolean accepted = enabled && renderer == wetInk && resumed;
                if (!accepted) {
                    Log.w(TAG, "enableWetInk: activation rejected, enabled=" + enabled
                            + " rendererStillCurrent=" + (renderer == wetInk) + " resumed="
                            + resumed);
                    destroyWetInk();
                    showFallbackSignal();
                } else {
                    showActiveSignal();
                }
                result.success(accepted);
            });
        } catch (RuntimeException | LinkageError error) {
            Log.w(TAG, "enableWetInk threw", error);
            destroyWetInk();
            showFallbackSignal();
            result.success(false);
        }
    }

    private boolean attach(StylusWetInkRenderer renderer) {
        View view = renderer.getView();
        ViewParent parent = view.getParent();
        if (parent != null) return parent instanceof ViewGroup;
        ViewGroup content = findViewById(android.R.id.content);
        if (content == null) return false;
        content.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return true;
    }

    @Nullable private View flutterView() {
        ViewGroup content = findViewById(android.R.id.content);
        if (content == null || content.getChildCount() == 0) return null;
        return content.getChildAt(content.getChildCount() - 1);
    }

    /** The extra wins; otherwise the launcher runs the default mode. */
    private StylusLatencyMode.Value resolveMode(Intent intent) {
        String raw = intent.getStringExtra(StylusLatencyMode.EXTRA);
        if (raw == null) {
            try {
                Bundle meta = getPackageManager().getActivityInfo(
                        getComponentName(), PackageManager.GET_META_DATA).metaData;
                if (meta != null) raw = meta.getString(StylusLatencyMode.EXTRA);
            } catch (PackageManager.NameNotFoundException ignored) {
                // Fall through to the default mode.
            }
        }
        return StylusLatencyMode.parse(raw);
    }

    /**
     * Milliseconds of motion prediction to request from the wet-ink overlay; 0 disables
     * prediction. Unset (extra absent) falls back to {@link #DEFAULT_PREDICTION_MS}; a
     * non-integer or unparsable extra also falls back to the default rather than crashing.
     */
    private int resolvePredictionMs(Intent intent) {
        if (!intent.hasExtra(PREDICTION_MS_EXTRA)) return DEFAULT_PREDICTION_MS;
        int value = intent.getIntExtra(PREDICTION_MS_EXTRA, DEFAULT_PREDICTION_MS);
        return Math.max(0, value);
    }

    @Override protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        mode = resolveMode(intent);
        predictionMs = resolvePredictionMs(intent);
        destroyWetInk();
        requestedSignalShown = false;
        showRequestedSignal();
        if (mode == StylusLatencyMode.Value.FLUTTER_ONLY) showActiveSignal();
        MethodChannel currentChannel = channel;
        if (currentChannel != null) currentChannel.invokeMethod("armChanged", mode.name());
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        StylusWetInkRenderer renderer = wetInk;
        long rendererGeneration = configuredGeneration;
        if (renderer != null) {
            try {
                renderer.onMotionEvent(event);
            } catch (RuntimeException | LinkageError error) {
                onRendererFailure(renderer, rendererGeneration);
            }
        }
        return super.dispatchTouchEvent(event);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        applyDisplayPreference();
    }

    @Override protected void onPause() {
        resumed = false;
        destroyWetInk();
        clearDisplayPreference();
        super.onPause();
    }

    @Override protected void onDestroy() {
        destroyWetInk();
        super.onDestroy();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        focused = hasFocus;
        applyDisplayPreference();
    }

    @Override public void onMultiWindowModeChanged(boolean value) {
        super.onMultiWindowModeChanged(value);
        multiWindow = value;
        applyDisplayPreference();
    }

    @Override public void onPictureInPictureModeChanged(boolean value) {
        super.onPictureInPictureModeChanged(value);
        pictureInPicture = value;
        applyDisplayPreference();
    }

    private void onRendererFailure(StylusWetInkRenderer renderer, long failureGeneration) {
        runOnUiThread(() -> {
            if (renderer != wetInk || failureGeneration != configuredGeneration) {
                Log.w(TAG, "onRendererFailure: stale callback ignored, generation="
                        + failureGeneration + " configuredGeneration=" + configuredGeneration
                        + " rendererStillCurrent=" + (renderer == wetInk));
                return;
            }
            Log.w(TAG, "onRendererFailure: FailureCallback.onFailure fired, generation="
                    + failureGeneration + " mode=" + mode);
            destroyWetInk();
            showFallbackSignal();
            MethodChannel currentChannel = channel;
            if (currentChannel != null) {
                currentChannel.invokeMethod("nativeFailure", failureGeneration);
            }
        });
    }

    private void destroyWetInk() {
        StylusWetInkRenderer renderer = wetInk;
        wetInk = null;
        configuredGeneration = Long.MIN_VALUE;
        if (renderer == null) return;
        try { renderer.destroy(); } catch (RuntimeException | LinkageError ignored) {}
        try {
            ViewParent parent = renderer.getView().getParent();
            if (parent instanceof ViewGroup group) group.removeView(renderer.getView());
        } catch (RuntimeException | LinkageError ignored) {}
    }

    private void applyDisplayPreference() {
        if (!resumed || !focused || multiWindow || pictureInPicture) {
            clearDisplayPreference();
            return;
        }
        Display display;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display = getDisplay();
        } else {
            display = getWindowManager().getDefaultDisplay();
        }
        if (display != null) {
            Display.Mode current = display.getMode();
            Display.Mode fastest = current;
            for (Display.Mode candidate : display.getSupportedModes()) {
                if (candidate.getPhysicalWidth() == current.getPhysicalWidth()
                        && candidate.getPhysicalHeight() == current.getPhysicalHeight()
                        && candidate.getRefreshRate() > fastest.getRefreshRate()) {
                    fastest = candidate;
                }
            }
            WindowManager.LayoutParams attributes = getWindow().getAttributes();
            attributes.preferredDisplayModeId = fastest.getModeId();
            getWindow().setAttributes(attributes);
        }
        setFullscreen(true);
    }

    private void clearDisplayPreference() {
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        if (attributes.preferredDisplayModeId != 0) {
            attributes.preferredDisplayModeId = 0;
            getWindow().setAttributes(attributes);
        }
        setFullscreen(false);
    }

    @SuppressWarnings("deprecation")
    private void setFullscreen(boolean enabled) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller == null) return;
            if (enabled) {
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.systemBars());
            } else {
                controller.show(WindowInsets.Type.systemBars());
            }
            return;
        }
        getWindow().getDecorView().setSystemUiVisibility(enabled
                ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                : View.SYSTEM_UI_FLAG_VISIBLE);
    }

    private void showRequestedSignal() {
        if (requestedSignalShown) return;
        requestedSignalShown = true;
        Toast.makeText(this, "Latency Lab requested: " + mode.name(), Toast.LENGTH_SHORT).show();
    }

    private void showActiveSignal() {
        Toast.makeText(this, "Latency Lab active: " + mode.name(), Toast.LENGTH_SHORT).show();
    }

    private void showFallbackSignal() {
        Toast.makeText(this, "Latency Lab fallback: " + mode.name(), Toast.LENGTH_SHORT).show();
    }

    private boolean matchesConfiguredGeneration(Object arguments) {
        return configuredGeneration != Long.MIN_VALUE
                && configuredGeneration == generation(arguments);
    }

    private static long generation(Object arguments) {
        if (!(arguments instanceof Map)) return Long.MIN_VALUE;
        Object value = ((Map<?, ?>) arguments).get("generation");
        return value instanceof Number ? ((Number) value).longValue() : Long.MIN_VALUE;
    }
}
