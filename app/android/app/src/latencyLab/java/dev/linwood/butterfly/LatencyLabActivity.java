package dev.linwood.butterfly;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
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
    private static final String CHANNEL = "linwood.dev/butterfly/ink";

    private StylusLatencyMode.Value mode = StylusLatencyMode.Value.FLUTTER_ONLY;
    @Nullable private StylusWetInkRenderer wetInk;
    private long configuredGeneration = Long.MIN_VALUE;
    private boolean resumed, focused, multiWindow, pictureInPicture;
    @Nullable private MethodChannel channel;
    private boolean requestedSignalShown;
    private int lifecycleEpoch;

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        mode = StylusLatencyMode.parse(getIntent().getStringExtra(StylusLatencyMode.EXTRA));
        super.onCreate(savedInstanceState);
        showRequestedSignal();
        if (mode == StylusLatencyMode.Value.FLUTTER_ONLY) showActiveSignal();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            multiWindow = isInMultiWindowMode();
            pictureInPicture = isInPictureInPictureMode();
        }
    }

    @Override public void configureFlutterEngine(@NonNull FlutterEngine engine) {
        super.configureFlutterEngine(engine);
        channel = new MethodChannel(engine.getDartExecutor().getBinaryMessenger(), CHANNEL);
        channel.setMethodCallHandler((call, result) -> {
                    switch (call.method) {
                        case "configure" -> {
                            boolean configured = configureWetInk(call.arguments);
                            if (!configured) showFallbackSignal();
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
                case INK_PREDICTION_OFF -> new InkLatencyOverlay(this, flutterView, false);
                case INK_PREDICTION_ON -> new InkLatencyOverlay(this, flutterView, true);
                case SHARED_GEOMETRY -> new SharedGeometryInkOverlay(this, flutterView);
                default -> null;
            };
            StylusWetInkRenderer renderer = wetInk;
            if (renderer == null || !renderer.configure(arguments)) {
                destroyWetInk();
                return false;
            }
            configuredGeneration = generation(arguments);
            return configuredGeneration != Long.MIN_VALUE;
        } catch (RuntimeException | LinkageError error) {
            destroyWetInk();
            return false;
        }
    }

    private void enableWetInk(Object arguments, MethodChannel.Result result) {
        try {
            StylusWetInkRenderer renderer = wetInk;
            if (renderer == null || !resumed || !matchesConfiguredGeneration(arguments)
                    || !renderer.enable(arguments) || !attach(renderer)) {
                destroyWetInk();
                result.success(false);
                showFallbackSignal();
                return;
            }
            int epoch = lifecycleEpoch;
            if (!renderer.attachAndPrewarm()) {
                destroyWetInk();
                result.success(false);
                showFallbackSignal();
                return;
            }
            renderer.awaitActivation(enabled -> {
                boolean accepted = enabled && renderer == wetInk && resumed
                        && epoch == lifecycleEpoch;
                if (!accepted) {
                    destroyWetInk();
                    showFallbackSignal();
                } else {
                    showActiveSignal();
                }
                result.success(accepted);
            });
        } catch (RuntimeException | LinkageError error) {
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

    @Override protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        mode = StylusLatencyMode.parse(intent.getStringExtra(StylusLatencyMode.EXTRA));
        destroyWetInk();
        requestedSignalShown = false;
        showRequestedSignal();
        if (mode == StylusLatencyMode.Value.FLUTTER_ONLY) showActiveSignal();
        MethodChannel currentChannel = channel;
        if (currentChannel != null) currentChannel.invokeMethod("armChanged", mode.name());
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        StylusWetInkRenderer renderer = wetInk;
        if (renderer != null) {
            try {
                renderer.onMotionEvent(event);
            } catch (RuntimeException | LinkageError error) {
                destroyWetInk();
            }
        }
        return super.dispatchTouchEvent(event);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        lifecycleEpoch++;
        applyDisplayPreference();
    }

    @Override protected void onPause() {
        resumed = false;
        lifecycleEpoch++;
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
