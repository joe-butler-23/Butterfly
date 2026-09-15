package dev.linwood.butterfly;

import androidx.annotation.Nullable;

final class StylusLatencyMode {
    static final String EXTRA = "dev.linwood.butterfly.extra.STYLUS_LATENCY_MODE";

    enum Value { FLUTTER_ONLY, SHARED_GEOMETRY }

    private StylusLatencyMode() {}

    /** The launcher runs shared-geometry front-buffered ink; the adb extra selects flutter-only. */
    static final Value DEFAULT = Value.SHARED_GEOMETRY;

    static Value parse(@Nullable String raw) {
        if (raw == null) return DEFAULT;
        return switch (raw) {
            case "flutter_only" -> Value.FLUTTER_ONLY;
            case "shared_geometry" -> Value.SHARED_GEOMETRY;
            default -> DEFAULT;
        };
    }
}
