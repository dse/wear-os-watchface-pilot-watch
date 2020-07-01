package com.webonastick.watchface.pilotwatch;

public class Utility {
    public static float mod(float x, float y) {
        return x - (float) Math.floor(x / y) * y;
    }

    public static float clamp(float x, float min, float max) {
        float result = x;
        result = Math.min(result, max);
        result = Math.max(result, min);
        return result;
    }

    enum BezelType {
        BEZEL_NONE,
        BEZEL_SLIDE_RULE,
        BEZEL_TACHYMETER
    }

    enum WatchDialTextDirection {
        TEXT_DIRECTION_HORIZONTAL,
        TEXT_DIRECTION_TANGENTIAL,
        TEXT_DIRECTION_RADIAL
    }

    enum SlideRuleDial {
        SLIDE_RULE_DIAL_INNER,
        SLIDE_RULE_DIAL_OUTER
    }

    enum WatchDialBorderStyle {
        NONE,
        SOLID,
        INSET,
        OUTSET
    }

    enum WatchDialBackgroundStyle {
        NONE,
        RADIAL_RIDGED
    }
}
