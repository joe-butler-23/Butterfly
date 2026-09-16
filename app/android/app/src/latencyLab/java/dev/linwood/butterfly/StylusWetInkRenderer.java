package dev.linwood.butterfly;

import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;

interface StylusWetInkRenderer {
    interface ActivationCallback { void onActivated(boolean enabled); }
    interface FailureCallback { void onFailure(long generation); }
    interface StrokeAbortedCallback {
        void onAborted(long generation, long sourceTimestampUs);
    }

    View getView();
    void setFailureCallback(FailureCallback callback);
    void setStrokeAbortedCallback(StrokeAbortedCallback callback);
    boolean configure(Object arguments);
    boolean enable(Object arguments);
    void awaitActivation(ActivationCallback callback);
    boolean attachAndPrewarm();
    void disable();
    void onMotionEvent(@NonNull MotionEvent event);
    boolean registerStroke(Object arguments);
    void acknowledgeStroke(Object arguments, Runnable completion);
    void cancelRegisteredStroke(Object arguments);
    void destroy();
}
