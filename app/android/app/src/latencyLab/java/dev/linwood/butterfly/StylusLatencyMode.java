package dev.linwood.butterfly;

import androidx.annotation.Nullable;

final class StylusLatencyMode {
    static final String EXTRA = "dev.linwood.butterfly.extra.STYLUS_LATENCY_MODE";

    enum Value { FLUTTER_ONLY, INK_PREDICTION_OFF, INK_PREDICTION_ON, SHARED_GEOMETRY }

    private StylusLatencyMode() {}

    static Value parse(@Nullable String raw) {
        if (raw == null) return Value.FLUTTER_ONLY;
        return switch (raw) {
            case "ink_prediction_off" -> Value.INK_PREDICTION_OFF;
            case "ink_prediction_on" -> Value.INK_PREDICTION_ON;
            case "shared_geometry" -> Value.SHARED_GEOMETRY;
            default -> Value.FLUTTER_ONLY;
        };
    }
}
