package android.view;

import android.os.Looper;

/** Compile-only signature from this device's Android 16 framework; NOT packaged. */
public abstract class DisplayEventReceiver {
    public DisplayEventReceiver(Looper looper, int vsyncSource, int eventRegistration) {
        throw new UnsupportedOperationException("Compile-only framework signature");
    }
    public void onModeChanged(long timestamp, long physicalDisplayId, int modeId, long renderPeriod) {}
    public void dispose() {}
}
