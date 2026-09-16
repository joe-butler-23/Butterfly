import android.os.Looper;
import android.view.DisplayEventReceiver;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;

/** Shell-only session workaround for the TCL 9469X Android 16 pen refresh policy. */
public final class Refresh120 {
    private static final String[] KEYS = {"peak_refresh_rate", "min_refresh_rate"};
    private static final long MAX_120_HZ_PERIOD_NS = 9_000_000L;
    private static final int SETTINGS_COMMAND_DEADLINE_SECONDS = 5;

    private static void restore() throws Exception {
        for (String key : KEYS) {
            java.lang.Process command = new ProcessBuilder("/system/bin/cmd", "settings",
                    "put", "system", key, "120.0").inheritIO().start();
            if (!command.waitFor(SETTINGS_COMMAND_DEADLINE_SECONDS,
                    java.util.concurrent.TimeUnit.SECONDS)) {
                command.destroyForcibly();
                throw new IllegalStateException("Settings command timed out: " + key);
            }
            if (command.exitValue() != 0) throw new IllegalStateException("Write failed: " + key);
        }
        System.out.println("REQUESTED 120 Hz");
        System.out.flush();
    }

    public static void main(String[] args) {
        try {
            if (args.length != 1) throw new IllegalArgumentException("Expected physical display ID from dumpsys SurfaceFlinger --display-id");
            run(Long.parseLong(args[0]));
        } catch (Throwable error) {
            error.printStackTrace(System.err);
            System.err.flush();
            System.exit(1);
        }
    }

    private static void run(long targetDisplayId) throws Exception {
        if (android.os.Process.myUid() != 2000) {
            throw new IllegalStateException("Launch through adb shell, not as an app or root");
        }
        if (android.os.Build.VERSION.SDK_INT != 36 || !android.os.Build.MODEL.equals("9469X")) {
            throw new IllegalStateException("This helper is specific to TCL 9469X Android 16");
        }
        try (RandomAccessFile file = new RandomAccessFile(
                "/data/local/tmp/butterfly-refresh-120.lock", "rw");
             FileLock lock = file.getChannel().tryLock()) {
            if (lock == null) throw new IllegalStateException("ALREADY_RUNNING");
            Looper.prepare();
            // Existing framework event source, registered before the initial settings write.
            // 0 = VSYNC_SOURCE_APP; 1 = EVENT_REGISTRATION_MODE_CHANGED_FLAG.
            DisplayEventReceiver receiver = new DisplayEventReceiver(Looper.myLooper(), 0, 1) {
                private int previousMode = -1;
                private long previousPeriod = -1;

                @Override public void onModeChanged(long timestamp, long physicalDisplayId,
                        int modeId, long renderPeriod) {
                    if (physicalDisplayId != targetDisplayId
                            || (modeId == previousMode && renderPeriod == previousPeriod)) return;
                    previousMode = modeId;
                    previousPeriod = renderPeriod;
                    System.out.println("MODE id=" + modeId + " periodNs=" + renderPeriod);
                    System.out.flush();
                    // One attempt per real transition: never poll or spin against a fixed limit.
                    if (renderPeriod <= MAX_120_HZ_PERIOD_NS) return;
                    try {
                        restore();
                    } catch (Exception error) {
                        error.printStackTrace(System.err);
                        System.exit(1);
                    }
                }
            };
            try {
                restore();
                System.out.println("READY pid=" + android.os.Process.myPid());
                System.out.flush();
                Looper.loop();
            } finally {
                receiver.dispose();
            }
        }
    }
}
