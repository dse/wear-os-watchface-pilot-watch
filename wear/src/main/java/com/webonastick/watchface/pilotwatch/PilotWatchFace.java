package com.webonastick.watchface.pilotwatch;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import androidx.core.content.ContextCompat;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.util.Pair;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import static android.app.AlarmManager.RTC_WAKEUP;

public class PilotWatchFace extends CanvasWatchFaceService {
    private static final String TAG = "PilotWatchFace";

    /**
     * Updates rate in milliseconds for interactive mode. We update
     * once a second to advance the second hand.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in
     * interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<PilotWatchFace.Engine> mWeakReference;

        public EngineHandler(PilotWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            PilotWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    public static class MoreMath {
        public static float mod(float x, float y) {
            return x - (float) Math.floor(x / y) * y;
        }

        public static float window(float x, float min, float max) {
            float result = x;
            result = Math.min(result, max);
            result = Math.max(result, min);
            return result;
        }
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

    private static final float TEXT_ROTATION_FUDGE_FACTOR = 1f;
    private static final float TEXT_CAP_HEIGHT = 0.7f;

    /* On at least two round watches the outer border won't display unless you factor this. */
    private static final int ROUND_CHOPPED_PX = 1;

    private class Engine extends CanvasWatchFaceService.Engine implements MultiTapEventHandler {

        Engine() {
            super();
            // super(true); // when ready to mess with hardware acceleration
        }

        /* Handler to update the time once a second in interactive mode. */
        private final Handler mUpdateTimeHandler = new EngineHandler(this);
        private Calendar mCalendar;
        private final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        private boolean mRegisteredTimeZoneReceiver = false;
        private boolean mMuteMode;

        private boolean mAmbient;
        private boolean mLowBitAmbient;
        private boolean mBurnInProtection;

        private float mPixelDensity;

        private int mBackgroundColor;
        private int mHourHandColor;
        private int mMinuteHandColor;
        private int mSecondHandColor;
        private int mTickColor;

        private BezelType mBezelType = BezelType.BEZEL_NONE;
        private float mSlideRuleDiameter = 0.8f;
        private float mTachymeterDiameter = 0.9f;

        // watch canvas/surface
        private float mSurfaceCenterXPx;
        private float mSurfaceCenterYPx;
        private int mSurfaceWidthPx;
        private int mSurfaceHeightPx;
        private int mSurfaceVminPx;

        // entire dial
        private float mDialDiameterPx;
        private float mDialRadiusPx;

        // clock dial
        private float mClockDialDiameterPx;
        private float mClockDialRadiusPx;

        private Bitmap mBackgroundBitmap = null;
        private Bitmap mBackgroundBitmap2 = null;
        private Bitmap mBackgroundBitmapZoomDayDate = null;
        private Bitmap mBackgroundBitmapZoomDayDate2 = null;
        private Bitmap mAmbientBackgroundBitmap = null;
        private Bitmap mAmbientBackgroundBitmap2 = null;

        private long mUpdateRateMs = INTERACTIVE_UPDATE_RATE_MS;

        private boolean mPutChronographSecondsOnSubDial = true;

        private boolean mDemoTimeMode = false;
        private boolean mEmulatorMode = false;

        private static final float MINIMUM_STROKE_WIDTH_PX = 1f;
        private static final float DEFAULT_TEXT_SIZE_VMIN = 0.05f;

        private WatchDial mMainDial;
        private WatchDial mTopSubDial;
        private WatchDial mLeftSubDial;
        private WatchDial mBottomSubDial;
        private WatchDial mBatterySubDial;

        private WatchHand mHourHand;
        private WatchHand mMinuteHand;
        private WatchHand mSecondHand;

        private WatchHand mChronographSecondFractionHand;
        private WatchHand mSubdialSecondHand;
        private WatchHand mChronographMinuteHand;
        private WatchHand mChronographHourHand;
        private WatchHand mBatteryHand;

        private Typeface mTypeface = Typeface.SANS_SERIF;
        private Typeface mCondensedTypeface;

        private boolean mZoomDayDate = false;

        private float mWatchFaceNameTextSizeVmin = 0.04f;
        private float mWatchFaceNameLeftOffsetVmin = 0.26f;
        private float mWatchFaceNameTopOffsetVmin = 0.26f;

        private float mSlideRuleTextSizeVmin = 0.05f;
        private float mTachymeterTextSizeVmin = 0.05f;

        private float mDayDateTextSizeVmin = 0.07f;
        private float mDayDateOuterVmin = 0.88f;

        private float mDayDateTextSizePx;
        private float mDayWindowCenterXPx;
        private float mDateWindowCenterXPx;
        private float mDayDateTopPx;
        private float mDayDateBottomPx;
        private float mDayDateLeftPx;
        private float mDayDateRightPx;

        private Paint mDayTextPaint;
        private Paint mDateTextPaint;

        private boolean mStopwatchRunning = false;
        private boolean mStopwatchPaused = false;
        private long mStopwatchStartTimeMs = 0;
        private long mStopwatchTimeMs = 0;

        /**
         * For keeping the watch face on longer than the standard
         * period of time.
         */
        private int mCustomTimeoutSeconds = 0;
        private PowerManager mPowerManager = null;
        private PowerManager.WakeLock mWakeLock = null;
        private boolean mFullWakeLockDenied = false;

        private boolean mShowVersionNumber = false;

        private float mRidgeHighlight = 0.2f;
        private float mRidgeShadow = 0.8f;
        private float mBorderHighlight = 0.2f;
        private float mBorderShadow = 0.8f;

        private class WatchDial {
            public WeakReference<Engine> engineWeakReference;

            public float diameterVmin = 0.5f;
            public float centerXVmin = 0.0f;
            public float centerYVmin = 0.0f;
            public boolean nonAmbientOnly = false;
            public float startAngle = 0f;
            public float endAngle = 360f;
            public float excludeTicksFrom = 0f;
            public float excludeTicksTo = 0f;

            public float circle1Diameter = 0f;
            public float circle2Diameter = 0f;
            public float circleStrokeWidthVmin = 0f;
            public boolean circlesNonAmbientOnly = false;

            /* { 0.25f, "3" }, { 0.5f, "6" }, { 0.5f, "9" }, ... */
            public ArrayList<Pair<Float, String>> textPairs = new ArrayList<Pair<Float, String>>();

            public float textSizeVmin = DEFAULT_TEXT_SIZE_VMIN;

            public int backgroundColor = Color.TRANSPARENT;
            public float backgroundBrightness = 0f;

            public WatchDialTextDirection textDirection = WatchDialTextDirection.TEXT_DIRECTION_HORIZONTAL;

            private float radiusPx;
            private float contentRadiusPx;
            private float centerXPx;
            private float centerYPx;

            private float leftBoundaryPx;
            private float rightBoundaryPx;
            private float topBoundaryPx;
            private float bottomBoundaryPx;

            private int shadowColor = Color.BLACK;
            private float shadowDXPx = 0;
            private float shadowDYPx = 1;

            public WatchDialBorderStyle borderStyle = WatchDialBorderStyle.NONE;
            public WatchDialBackgroundStyle backgroundStyle = WatchDialBackgroundStyle.NONE;

            public float borderWidthVmin = 0f;
            private float borderWidthPx = 0f;
            public int borderColor = Color.TRANSPARENT;

            Shader borderOutsetShader;
            Shader borderInsetShader;
            Shader ridgeOutsetShader;
            Shader ridgeInsetShader;

            public Typeface typeface = null;

            public ArrayList<WatchDialTickSet> tickSets = new ArrayList<WatchDialTickSet>();

            public void addText(float rotation, String text) {
                textPairs.add(new Pair<>(rotation, text));
            }

            private boolean isExcluded(float rotation) {
                return (excludeTicksFrom != 0f || excludeTicksTo != 0f) &&
                        rotation > excludeTicksFrom && rotation < excludeTicksTo;
            }

            public WatchDial(Engine engine) {
                engineWeakReference = new WeakReference<Engine>(engine);
            }

            public void addTickSet(WatchDialTickSet tickSet) {
                tickSets.add(tickSet);
            }

            public void update() {
                Engine engine = engineWeakReference.get();
                radiusPx = diameterVmin * engine.mClockDialRadiusPx;
                borderWidthPx = vminToPx(borderWidthVmin, true);
                contentRadiusPx = radiusPx - borderWidthPx;
                centerXPx = engine.mSurfaceCenterXPx + centerXVmin * engine.mClockDialDiameterPx;
                centerYPx = engine.mSurfaceCenterYPx + centerYVmin * engine.mClockDialDiameterPx;

                float highlightOpacity;
                float shadowOpacity;
                int highlightAlpha;
                int shadowAlpha;
                int highlightColor;
                int shadowColor;
                int[] outsetColors;
                int[] insetColors;

                if (borderStyle == WatchDialBorderStyle.INSET || borderStyle == WatchDialBorderStyle.OUTSET) {
                    highlightOpacity = MoreMath.window(mBorderHighlight, 0f, 1f);
                    shadowOpacity = MoreMath.window(mBorderShadow, 0f, 1f);
                    highlightAlpha = Math.round(255 * highlightOpacity);
                    shadowAlpha = Math.round(255 * shadowOpacity);
                    highlightColor = ((0xff & highlightAlpha) << 24) | 0xffffff;
                    shadowColor = ((0xff & shadowAlpha) << 24) | 0x000000;
                    outsetColors = new int[]{Color.TRANSPARENT, shadowColor, Color.TRANSPARENT, highlightColor, Color.TRANSPARENT};
                    insetColors = new int[]{Color.TRANSPARENT, highlightColor, Color.TRANSPARENT, shadowColor, Color.TRANSPARENT};
                    borderOutsetShader = new SweepGradient(centerXPx, centerYPx, outsetColors, null);
                    borderInsetShader = new SweepGradient(centerXPx, centerYPx, insetColors, null);
                } else {
                    borderOutsetShader = null;
                    borderInsetShader = null;
                }

                if (backgroundStyle == WatchDialBackgroundStyle.RADIAL_RIDGED) {
                    highlightOpacity = MoreMath.window(mRidgeHighlight, 0f, 1f);
                    shadowOpacity = MoreMath.window(mRidgeShadow, 0f, 1f);
                    highlightAlpha = Math.round(255 * highlightOpacity);
                    shadowAlpha = Math.round(255 * shadowOpacity);
                    highlightColor = ((0xff & highlightAlpha) << 24) | 0xffffff;
                    shadowColor = ((0xff & shadowAlpha) << 24) | 0x000000;
                    outsetColors = new int[]{Color.TRANSPARENT, shadowColor, Color.TRANSPARENT, highlightColor, Color.TRANSPARENT};
                    insetColors = new int[]{Color.TRANSPARENT, highlightColor, Color.TRANSPARENT, shadowColor, Color.TRANSPARENT};
                    ridgeOutsetShader = new SweepGradient(centerXPx, centerYPx, outsetColors, null);
                    ridgeInsetShader = new SweepGradient(centerXPx, centerYPx, insetColors, null);
                } else {
                    ridgeOutsetShader = null;
                    ridgeInsetShader = null;
                }

                updateBoundaries();
            }

            private void updateBoundaries() {
                Engine engine = engineWeakReference.get();
                if (startAngle == 0f && endAngle == 360f) {
                    leftBoundaryPx = centerXPx - radiusPx;
                    rightBoundaryPx = centerXPx + radiusPx;
                    topBoundaryPx = centerYPx - radiusPx;
                    bottomBoundaryPx = centerYPx + radiusPx;
                } else {
                    leftBoundaryPx = centerXPx;
                    rightBoundaryPx = centerXPx;
                    topBoundaryPx = centerYPx;
                    bottomBoundaryPx = centerYPx;
                    float minAngle = Math.min(startAngle, endAngle);
                    float maxAngle = Math.max(startAngle, endAngle);
                    float angle = minAngle;
                    while (true) {
                        float pointXPx = centerXPx + radiusPx * (float) Math.sin(angle * Math.PI / 180.0);
                        float pointYPx = centerYPx - radiusPx * (float) Math.cos(angle * Math.PI / 180.0);
                        leftBoundaryPx = Math.min(leftBoundaryPx, pointXPx);
                        rightBoundaryPx = Math.max(rightBoundaryPx, pointXPx);
                        topBoundaryPx = Math.min(topBoundaryPx, pointYPx);
                        bottomBoundaryPx = Math.max(bottomBoundaryPx, pointYPx);
                        if (angle == maxAngle) {
                            break;
                        }
                        angle += 90f;                                  // e.g., 36 => 126
                        angle = (float) Math.floor(angle / 90f) * 90f; // e.g., 126 => 90
                        angle = Math.min(angle, maxAngle);
                    }
                }

                /* don't put boundaries past the boundaries of the canvas */
                leftBoundaryPx = Math.max(leftBoundaryPx, 0);
                rightBoundaryPx = Math.min(rightBoundaryPx, engine.mSurfaceWidthPx);
                topBoundaryPx = Math.max(topBoundaryPx, 0);
                bottomBoundaryPx = Math.min(bottomBoundaryPx, engine.mSurfaceHeightPx);
            }

            public void draw(Canvas canvas, boolean ambient) {
                Engine engine = engineWeakReference.get();

                if (ambient && nonAmbientOnly) {
                    return;
                }

                drawBackgroundColor(canvas, ambient);
                if (!ambient && shadowColor != 0 && (shadowDXPx != 0 || shadowDYPx != 0)) {
                    drawTicks(canvas, ambient, true);
                    drawCircles(canvas, ambient, true);
                    drawText(canvas, ambient, true);
                }
                drawTicks(canvas, ambient);
                drawCircles(canvas, ambient);
                drawText(canvas, ambient);
                drawBorder(canvas, ambient);
            }

            public void drawBackgroundColor(Canvas canvas, boolean ambient) {
                if (ambient) {
                    return;
                }
                Engine engine = engineWeakReference.get();

                Paint paint = new Paint();
                paint.setAntiAlias(true);
                paint.setColor(backgroundColor);
                paint.setStyle(Paint.Style.FILL);

                canvas.drawCircle(centerXPx, centerYPx, radiusPx, paint);

                if (backgroundBrightness < 0f) {
                    backgroundBrightness = -1 * MoreMath.window(backgroundBrightness, -1f, 0f);
                    int alpha = Math.round(255 * backgroundBrightness);
                    int color = (0xff & alpha) << 24 | 0x000000;
                    paint.setColor(color);
                    canvas.drawCircle(centerXPx, centerYPx, radiusPx, paint);
                } else if (backgroundBrightness > 0f) {
                    backgroundBrightness = MoreMath.window(backgroundBrightness, 0f, 1f);
                    int alpha = Math.round(255 * backgroundBrightness);
                    int color = (0xff & alpha) << 24 | 0xffffff;
                    paint.setColor(color);
                    canvas.drawCircle(centerXPx, centerYPx, radiusPx, paint);
                }

                drawBackgroundStyle(canvas, ambient);
            }

            public void drawBackgroundStyle(Canvas canvas, boolean ambient) {
                if (ambient || backgroundStyle == WatchDialBackgroundStyle.NONE) {
                    return;
                }
                switch (backgroundStyle) {
                    case RADIAL_RIDGED:
                        drawRadialRidgedBackground(canvas, ambient);
                        break;
                }
            }

            public void drawRadialRidgedBackground(Canvas canvas, boolean ambient) {
                if (ambient) {
                    return;
                }

                float ridgePx = dpToPx(1f);
                float radiusPx;
                float radiusIncr = ridgePx * 3f;

                boolean isInset = true;
                Paint paint = new Paint();
                paint.setAntiAlias(true);
                paint.setStrokeWidth(ridgePx);
                paint.setStyle(Paint.Style.STROKE);

                for (radiusPx = ridgePx / 2f;
                     radiusPx <= contentRadiusPx;
                     radiusPx += radiusIncr) {
                    if (circle1Diameter != 0f && radiusPx > circle1Diameter * contentRadiusPx) {
                        break;
                    }
                    if (circle2Diameter != 0f && radiusPx > circle2Diameter * contentRadiusPx) {
                        break;
                    }
                    paint.setShader(isInset ? ridgeInsetShader : ridgeOutsetShader);
                    drawArc(canvas, radiusPx, paint, false, true);
                    isInset = !isInset;
                }
            }

            public void drawTicks(Canvas canvas, boolean ambient) {
                drawTicks(canvas, ambient, false);
            }

            public void drawTicks(Canvas canvas, boolean ambient, boolean isShadow) {
                if (isShadow && (ambient || (shadowDXPx == 0 && shadowDYPx == 0))) {
                    return;
                }

                for (WatchDialTickSet tickSet : tickSets) {
                    tickSet.draw(canvas, ambient, isShadow);
                }
            }

            public float ticksInner() {
                if (tickSets == null || tickSets.isEmpty()) {
                    return 1.0f;
                }
                float result = 1.0f;
                boolean isFirst = true;
                for (WatchDialTickSet tickSet : tickSets) {
                    if (isFirst) {
                        result = tickSet.innerDiameter;
                    } else {
                        result = Math.min(result, tickSet.innerDiameter);
                    }
                    isFirst = false;
                }
                return result;
            }

            public float ticksOuter() {
                if (tickSets == null || tickSets.isEmpty()) {
                    return 1.0f;
                }
                float result = 1.0f;
                boolean isFirst = true;
                for (WatchDialTickSet tickSet : tickSets) {
                    if (isFirst) {
                        result = tickSet.outerDiameter;
                    } else {
                        result = Math.max(result, tickSet.outerDiameter);
                    }
                    isFirst = false;
                }
                return result;
            }

            public float getCircleStrokeWidth() {
                Engine engine = engineWeakReference.get();
                if (circleStrokeWidthVmin != 0f) {
                    return Math.max(MINIMUM_STROKE_WIDTH_PX, circleStrokeWidthVmin * engine.mSurfaceVminPx);
                }
                for (WatchDialTickSet tickSet : tickSets) {
                    if (tickSet.strokeWidthVmin != 0f) {
                        return Math.max(MINIMUM_STROKE_WIDTH_PX, tickSet.strokeWidthVmin * engine.mSurfaceVminPx);
                    }
                }
                return 0f;
            }

            public void drawCircles(Canvas canvas, boolean ambient) {
                drawCircles(canvas, ambient, false);
            }

            public void drawCircles(Canvas canvas, boolean ambient, boolean isShadow) {
                if (isShadow && (ambient || (shadowDXPx == 0 && shadowDYPx == 0))) {
                    return;
                }
                if (ambient && circlesNonAmbientOnly) {
                    return;
                }

                Engine engine = engineWeakReference.get();

                float strokeWidthPx = getCircleStrokeWidth();
                if (strokeWidthPx == 0f) {
                    return;
                }

                Paint paint = new Paint();
                paint.setAntiAlias(true);
                if (ambient) {
                    paint.setColor(Color.WHITE);
                } else {
                    if (isShadow) {
                        paint.setColor(Color.BLACK);
                    } else {
                        paint.setColor(engine.mTickColor);
                    }
                }
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeCap(Paint.Cap.BUTT);
                paint.setStrokeWidth(Math.max(MINIMUM_STROKE_WIDTH_PX, strokeWidthPx));

                if (circle1Diameter != 0f) {
                    drawArc(canvas, circle1Diameter, paint, isShadow);
                }
                if (circle2Diameter != 0f) {
                    drawArc(canvas, circle2Diameter, paint, isShadow);
                }
            }

            public void drawText(Canvas canvas, boolean ambient) {
                drawText(canvas, ambient, false);
            }

            public void drawText(Canvas canvas, boolean ambient, boolean isShadow) {
                if (isShadow && (ambient || (shadowDXPx == 0 && shadowDYPx == 0))) {
                    return;
                }
                if (textPairs == null || textPairs.isEmpty()) {
                    return;
                }

                Engine engine = engineWeakReference.get();

                float paddingPx = getPaddingPx();

                float centerXPx = this.centerXPx + (isShadow ? shadowDXPx : 0);
                float centerYPx = this.centerYPx + (isShadow ? shadowDYPx : 0);
                float textSizePx = engine.getClockDialTextSizePx(textSizeVmin);
                float ticksInner = this.ticksInner();

                Paint textPaint = new Paint();
                if (typeface != null) {
                    textPaint.setTypeface(typeface);
                } else {
                    textPaint.setTypeface(mTypeface);
                }
                if (isShadow) {
                    textPaint.setColor(Color.BLACK);
                } else {
                    textPaint.setColor(Color.WHITE);
                }
                textPaint.setTextSize(textSizePx);
                textPaint.setAntiAlias(true);
                textPaint.setStyle(Paint.Style.FILL);

                for (Pair<Float, String> textPair : textPairs) {
                    float rotation = textPair.first;
                    float angle = getCanvasRotationAngle(rotation);
                    String text = textPair.second;

                    float textYPx = centerYPx - contentRadiusPx * 0.6f; /* initialize for TEXT_DIRECTION_HORIZONTAL */
                    switch (textDirection) {
                        case TEXT_DIRECTION_TANGENTIAL:
                            textYPx = centerYPx - contentRadiusPx * ticksInner + textSizePx * TEXT_CAP_HEIGHT / 2f + paddingPx;
                            break;
                        case TEXT_DIRECTION_RADIAL:
                            textYPx = centerYPx - contentRadiusPx * ticksInner + paddingPx;
                            break;
                    }

                    float textAngle = 0f; /* initialize for TEXT_DIRECTION_HORIZONTAL */
                    Paint.Align textAlign = Paint.Align.CENTER;
                    switch (textDirection) {
                        case TEXT_DIRECTION_TANGENTIAL:
                            textAngle = MoreMath.mod(angle, 360f);
                            break;
                        case TEXT_DIRECTION_RADIAL:
                            textAngle = MoreMath.mod(angle + 90f, 360f);
                            break;
                    }
                    if (textAngle != 0f) {
                        if (textDirection == WatchDialTextDirection.TEXT_DIRECTION_RADIAL) {
                            textAlign = Paint.Align.RIGHT;
                        }
                        if (textAngle >= (90f + TEXT_ROTATION_FUDGE_FACTOR) &&
                                textAngle <= (270f - TEXT_ROTATION_FUDGE_FACTOR)) {
                            textAngle = MoreMath.mod(textAngle + 180f, 360f);
                            if (textDirection == WatchDialTextDirection.TEXT_DIRECTION_TANGENTIAL) {
                                textAlign = Paint.Align.LEFT;
                            }
                        }
                    }

                    textPaint.setTextAlign(textAlign);

                    Rect bounds = new Rect();
                    textPaint.getTextBounds(text, 0, text.length(), bounds);

                    canvas.save();
                    canvas.rotate(angle, centerXPx, centerYPx);
                    canvas.rotate(-angle, centerXPx, textYPx); /* initialize for TEXT_DIRECTION_HORIZONTAL */
                    canvas.rotate(textAngle, centerXPx, textYPx);
                    drawVerticallyCenteredText(canvas, text, centerXPx, textYPx, textPaint);
                    canvas.restore();
                }
            }

            public void drawArc(Canvas canvas, float diameterVmin, Paint paint, boolean isShadow) {
                drawArc(canvas, diameterVmin, paint, isShadow, false);
            }

            public void drawArc(Canvas canvas, float diameterVmin, Paint paint, boolean isShadow, boolean isPixels) {
                float startAngle = this.startAngle;
                float endAngle = this.endAngle;
                if (startAngle > endAngle) {
                    startAngle = this.endAngle;
                    endAngle = this.startAngle;
                }

                float centerXPx = this.centerXPx + (isShadow ? shadowDXPx : 0);
                float centerYPx = this.centerYPx + (isShadow ? shadowDYPx : 0);
                float px = isPixels ? diameterVmin : diameterVmin * contentRadiusPx;

                if (excludeTicksFrom == 0f && excludeTicksTo == 0f) {
                    canvas.drawArc(
                            centerXPx - px, centerYPx - px,
                            centerXPx + px, centerYPx + px,
                            startAngle - 90f,
                            endAngle - startAngle,
                            false, paint
                    );
                } else {
                    canvas.drawArc(
                            centerXPx - px, centerYPx - px,
                            centerXPx + px, centerYPx + px,
                            startAngle - 90f,
                            (endAngle - startAngle) * excludeTicksFrom,
                            false, paint
                    );
                    canvas.drawArc(
                            centerXPx - px, centerYPx - px,
                            centerXPx + px, centerYPx + px,
                            startAngle - 90f + (endAngle - startAngle) * excludeTicksTo,
                            endAngle - startAngle - (endAngle - startAngle) * excludeTicksTo,
                            false, paint
                    );
                }
            }

            public boolean containsAngle(float angle) {
                float maxAngle = Math.max(startAngle, endAngle);
                float minAngle = Math.min(startAngle, endAngle);
                angle = angle - MoreMath.mod(minAngle, 360f) + minAngle;
                return angle >= minAngle && angle <= maxAngle;
            }

            public boolean contains(int x, int y) {
                float dx = 0.0f + x - centerXPx;
                float dy = 0.0f + y - centerYPx;
                if (dx == 0.0f && dy == 0.0f) {
                    return true;
                }
                if (!(startAngle == 0f && endAngle == 360f)) { /* defaults */
                    float angle = (float) Math.atan2(dx, -dy) * 180f / (float) Math.PI;
                    if (!containsAngle(angle)) {
                        return false;
                    }
                }
                return dx * dx + dy * dy <= radiusPx * radiusPx;
            }

            public boolean isToTheRightOf(int x) {
                return leftBoundaryPx > x;
            }

            public boolean isToTheLeftOf(int x) {
                return rightBoundaryPx < x;
            }

            public boolean isAbove(int y) {
                return bottomBoundaryPx < y;
            }

            public boolean isBelow(int y) {
                return topBoundaryPx > y;
            }

            public float getCanvasRotationAngle(float rotation) {
                return startAngle + (endAngle - startAngle) * rotation;
            }

            public float getArcRotationAngle(float rotation) {
                return startAngle - 90f + (endAngle - startAngle) * rotation;
            }

            public float getArcSweepAngle(float startRotation, float endRotation) {
                return (endAngle - startAngle) * (endRotation - startRotation);
            }

            private void drawBorder(Canvas canvas, boolean ambient) {
                if (ambient || borderWidthPx <= 0f || borderStyle == WatchDialBorderStyle.NONE ||
                        (borderColor == Color.TRANSPARENT && borderStyle == WatchDialBorderStyle.SOLID)) {
                    return;
                }
                Paint borderPaint = new Paint();
                borderPaint.setAntiAlias(true);
                borderPaint.setStyle(Paint.Style.STROKE);
                borderPaint.setStrokeWidth(borderWidthPx);
                switch (borderStyle) {
                    case SOLID:
                        borderPaint.setColor(borderColor);
                        break;
                    case INSET:
                        borderPaint.setShader(borderInsetShader);
                        break;
                    case OUTSET:
                        borderPaint.setShader(borderOutsetShader);
                        break;
                }
                drawArc(canvas, radiusPx - borderWidthPx / 2f, borderPaint, false, true);
            }

            public void zoom(Canvas canvas) {
                Engine engine = engineWeakReference.get();
                float newCenterX = (leftBoundaryPx + rightBoundaryPx) / 2f;
                float newCenterY = (topBoundaryPx + bottomBoundaryPx) / 2f;
                float dx = newCenterX - centerXPx;
                float dy = newCenterY - centerYPx;
                float paddingPx = getPaddingPx();
                float scale = Math.min(
                        engine.mSurfaceWidthPx / (rightBoundaryPx - leftBoundaryPx + paddingPx * 2),
                        engine.mSurfaceHeightPx / (bottomBoundaryPx - topBoundaryPx + paddingPx * 2)
                );
                canvas.scale(scale, scale, newCenterX, newCenterY);
                canvas.translate(-dx, -dy);
            }
        }

        private class WatchDialTickSet {
            public WeakReference<WatchDial> watchDialWeakReference;

            public WatchDialTickSet(WatchDial watchDial) {
                watchDialWeakReference = new WeakReference<WatchDial>(watchDial);
            }

            public int numberOfTicks = 4;
            public float outerDiameter = 1.0f;
            public float innerDiameter = 0.9f;
            public float ambientOuterDiameter = -1;
            public float ambientInnerDiameter = -1;
            public float strokeWidthVmin = 0.01f;
            public float ambientStrokeWidthVmin = -1;
            public boolean nonAmbientOnly = false;
            public ArrayList<Integer> excludeNumberOfTicks = new ArrayList<Integer>();

            public void excludeTicks(WatchDialTickSet ts) {
                excludeNumberOfTicks.add(ts.numberOfTicks);
            }

            public void draw(Canvas canvas, boolean ambient) {
                draw(canvas, ambient, false);
            }

            public void draw(Canvas canvas, boolean ambient, boolean isShadow) {
                WatchDial watchDial = watchDialWeakReference.get();
                Engine engine = watchDial.engineWeakReference.get();

                if (ambient && nonAmbientOnly) {
                    return;
                }

                float outerDiameter = this.outerDiameter;
                float innerDiameter = this.innerDiameter;
                float strokeWidthVmin = this.strokeWidthVmin;
                if (ambient) {
                    if (ambientInnerDiameter >= 0) {
                        innerDiameter = ambientInnerDiameter;
                    }
                    if (ambientOuterDiameter >= 0) {
                        outerDiameter = ambientOuterDiameter;
                    }
                    if (ambientStrokeWidthVmin >= 0) {
                        strokeWidthVmin = ambientStrokeWidthVmin;
                    }
                }

                float centerXPx = watchDial.centerXPx + (isShadow ? watchDial.shadowDXPx : 0);
                float centerYPx = watchDial.centerYPx + (isShadow ? watchDial.shadowDYPx : 0);

                Paint paint = new Paint();
                paint.setAntiAlias(true);
                if (ambient) {
                    paint.setColor(Color.WHITE);
                } else {
                    if (isShadow) {
                        paint.setColor(Color.BLACK);
                    } else {
                        paint.setColor(engine.mTickColor);
                    }
                }
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeCap(Paint.Cap.BUTT);

                float extendPx = watchDial.getCircleStrokeWidth() * 0.45f;
                paint.setStrokeWidth(Math.max(MINIMUM_STROKE_WIDTH_PX, strokeWidthVmin * engine.mSurfaceVminPx));

                float y1 = centerYPx - outerDiameter * watchDial.contentRadiusPx;
                float y2 = centerYPx - innerDiameter * watchDial.contentRadiusPx;

                tick:
                for (int i = 0; i <= numberOfTicks; i += 1) {
                    for (int n : excludeNumberOfTicks) {
                        if ((i * n) % numberOfTicks == 0) {
                            continue tick;
                        }
                    }
                    float rotation = 1.0f * i / numberOfTicks;
                    if (watchDial.isExcluded(rotation)) {
                        continue tick;
                    }
                    float angle = watchDial.startAngle +
                            (watchDial.endAngle - watchDial.startAngle) * rotation;
                    canvas.save();
                    canvas.rotate(angle, centerXPx, centerYPx);
                    boolean extend = false;
                    if (watchDial.startAngle != watchDial.endAngle) {
                        if (angle == watchDial.startAngle || angle == watchDial.endAngle) {
                            extend = true;
                        }
                    }
                    if (watchDial.excludeTicksFrom != watchDial.excludeTicksTo) {
                        if (rotation == watchDial.excludeTicksFrom || rotation == watchDial.excludeTicksTo) {
                            extend = true;
                        }
                    }
                    if (extend) {
                        canvas.drawLine(centerXPx, y1 - extendPx, centerXPx, y2 + extendPx, paint);
                    } else {
                        canvas.drawLine(centerXPx, y1, centerXPx, y2, paint);
                    }
                    canvas.restore();
                }
            }
        }

        private void zoomCanvas(Canvas canvas, float x1, float x2, float y1, float y2) {
            float fudge = Math.min(canvas.getWidth(), canvas.getHeight()) * 0.02f;
            zoomCanvas(canvas, x1, x2, y1, y2, fudge);
        }

        private void zoomCanvas(Canvas canvas, float x1, float x2, float y1, float y2, float fudge) {
            x1 = Math.max(x1 - fudge, 0);
            x2 = Math.min(x2 + fudge, canvas.getWidth());
            y1 = Math.max(y1 - fudge, 0);
            y2 = Math.min(y2 + fudge, canvas.getHeight());
            float centerX = (x1 + x2) / 2f;
            float centerY = (y1 + y2) / 2f;
            float dx = centerX - mSurfaceCenterXPx;
            float dy = centerY - mSurfaceCenterYPx;
            float scaleX = mSurfaceWidthPx / (x2 - x1);
            float scaleY = mSurfaceHeightPx / (y2 - y1);
            float scale = Math.min(scaleX, scaleY);
            canvas.scale(scale, scale, mSurfaceCenterXPx, mSurfaceCenterYPx);
            canvas.translate(-dx, -dy);
        }

        private class WatchHand {
            public WeakReference<WatchDial> watchDialWeakReference;

            public Paint paint;
            public Path path;
            public int color;
            public boolean nonAmbientOnly = false;
            public boolean hasArrowHead = false;
            public float arrowHeadAngle = 45f;
            public float arrowHeadSize = 3f;
            public float lengthPctRadius = 1f;
            public float lengthBehindPctRadius = 0f;
            public float widthVmin = 0.005f;
            public float shroudThingyRadius = 0.03f;
            public float shroudThingyHoleRadius = 0.01f;

            private float lengthPx;
            private float lengthBehindPx;
            private float widthPx;
            private float shroudThingyRadiusPx;
            private float shroudThingyHoleRadiusPx;

            private float shadowRadiusPx = 0f;
            private int shadowColor = Color.BLACK;

            public WatchHand(WatchDial watchDial) {
                watchDialWeakReference = new WeakReference<WatchDial>(watchDial);
            }

            public void updateDimensions() {
                WatchDial dial = watchDialWeakReference.get();
                Engine engine = dial.engineWeakReference.get();

                lengthPx = lengthPctRadius * dial.contentRadiusPx;
                lengthBehindPx = lengthBehindPctRadius * dial.contentRadiusPx;
                widthPx = widthVmin * engine.mClockDialDiameterPx;

                shroudThingyHoleRadiusPx = shroudThingyHoleRadius * engine.mClockDialRadiusPx;
                shroudThingyRadiusPx = shroudThingyRadius * engine.mClockDialRadiusPx;
                if (shroudThingyRadiusPx < widthPx) {
                    shroudThingyRadiusPx = widthPx;
                }
            }

            public void updatePaint() {
                WatchDial dial = watchDialWeakReference.get();
                Engine engine = dial.engineWeakReference.get();
                paint = new Paint();
                paint.setStyle(Paint.Style.FILL);
                if (engine.mAmbient) {
                    paint.setColor(Color.WHITE);
                    paint.clearShadowLayer();
                } else {
                    paint.setColor(color);
                    if (shadowColor != 0 && shadowRadiusPx != 0f) {
                        paint.setShadowLayer(shadowRadiusPx, 0, 0, shadowColor);
                    } else {
                        paint.clearShadowLayer();
                    }
                }
                if (engine.mLowBitAmbient) {
                    paint.setAntiAlias(false);
                } else {
                    paint.setAntiAlias(true);
                }
            }

            public void updatePath() {
                WatchDial dial = watchDialWeakReference.get();

                path = new Path();

                float leftPx = dial.centerXPx - widthPx / 2;
                float rightPx = dial.centerXPx + widthPx / 2;
                float topPx = dial.centerYPx - lengthPx;
                float bottomPx = dial.centerYPx + lengthBehindPx;

                if (hasArrowHead) {
                    float arrowheadDX1 = widthPx * arrowHeadSize / 2;
                    float arrowheadY1 = topPx + widthPx * arrowHeadSize / 2 / (float) Math.tan(((float) Math.PI) / 180f * arrowHeadAngle / 2);
                    path.moveTo(leftPx, bottomPx);
                    path.lineTo(leftPx, arrowheadY1);
                    path.lineTo(dial.centerXPx - arrowheadDX1, arrowheadY1);
                    path.lineTo(dial.centerXPx, topPx);
                    path.lineTo(dial.centerXPx + arrowheadDX1, arrowheadY1);
                    path.lineTo(rightPx, arrowheadY1);
                    path.lineTo(rightPx, bottomPx);
                    path.close();
                } else {
                    float tipHeight = widthPx / 2 / (float) Math.tan(((float) Math.PI) / 180f * arrowHeadAngle / 2);
                    path.moveTo(leftPx, bottomPx);
                    path.lineTo(leftPx, topPx + tipHeight);
                    path.lineTo(dial.centerXPx, topPx);
                    path.lineTo(rightPx, topPx + tipHeight);
                    path.lineTo(rightPx, bottomPx);
                    path.close();
                }

                Path circlePath = new Path();
                circlePath.addCircle(dial.centerXPx, dial.centerYPx, shroudThingyRadiusPx, Path.Direction.CW);
                path.op(circlePath, Path.Op.UNION);

                circlePath = new Path();
                circlePath.addCircle(dial.centerXPx, dial.centerYPx, shroudThingyHoleRadiusPx, Path.Direction.CW);
                path.op(circlePath, Path.Op.DIFFERENCE);
            }

            public void update() {
                updateDimensions();
                updatePaint();
                updatePath();
            }

            public void draw(Canvas canvas, float rotation) {
                WatchDial dial = watchDialWeakReference.get();
                Engine engine = dial.engineWeakReference.get();

                float angle = dial.startAngle + (dial.endAngle - dial.startAngle) * rotation;

                if (engine.mAmbient && (dial.nonAmbientOnly || nonAmbientOnly)) {
                    return;
                }

                canvas.save();
                canvas.rotate(angle, dial.centerXPx, dial.centerYPx);
                canvas.drawPath(path, paint);
                canvas.restore();
            }
        }

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            cancelMultiTap();

            if (Build.MODEL.startsWith("sdk_") || Build.FINGERPRINT.contains("/sdk_")) {
                mEmulatorMode = true;
            }

            setWatchFaceStyle(new WatchFaceStyle.Builder(PilotWatchFace.this)
                    .setAcceptsTapEvents(true)
                    .build());

            mCalendar = Calendar.getInstance();

            mPixelDensity = getResources().getDisplayMetrics().density;

            mCondensedTypeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL);

            setUpdateRate();

            initColors();
            initDials();
            initHands();

            clearIdle();
            updateDials();
            updateHands();

            setCustomTimeout(15);
        }

        @Override
        public void onDestroy() {
            cancelMultiTap();
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            cancelMultiTap();
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                unregisterReceiver();
            }

            /* Check and trigger whether or not timer should be running (only in active mode). */
            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            cancelMultiTap();
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
            mBurnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false);
            mPixelDensity = getResources().getDisplayMetrics().density;
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            cancelMultiTap();
            super.onAmbientModeChanged(inAmbientMode);
            mAmbient = inAmbientMode;
            if (mShowVersionNumber) {
                mShowVersionNumber = false;
                mBackgroundBitmap2 = null;
            }

            if (mAmbient) {
                mZoomDayDate = false;
                updateDials();
                updateHands();
                startAmbientUpdates();
            } else {
                stopAmbientUpdates();
                updateDials();
                updateHands();
                updateTimer();
                clearIdle();
            }
        }

        @Override
        public void onInterruptionFilterChanged(int interruptionFilter) {
            cancelMultiTap();
            super.onInterruptionFilterChanged(interruptionFilter);
            boolean inMuteMode = (interruptionFilter == WatchFaceService.INTERRUPTION_FILTER_NONE);

            /* Dim display in mute mode. */
            if (mMuteMode != inMuteMode) {
                mMuteMode = inMuteMode;
                invalidate();
            }
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            cancelMultiTap();
            super.onSurfaceChanged(holder, format, width, height);

            mPixelDensity = getResources().getDisplayMetrics().density;

            mZoomDayDate = false;
            mShowVersionNumber = false;

            mSurfaceCenterXPx = width / 2f;
            mSurfaceCenterYPx = height / 2f;
            mSurfaceWidthPx = width;
            mSurfaceHeightPx = height;
            mSurfaceVminPx = Math.min(width, height);

            mDialDiameterPx = mSurfaceVminPx - MINIMUM_STROKE_WIDTH_PX;
            if (getApplicationContext().getResources().getConfiguration().isScreenRound()) {
                mDialDiameterPx -= ROUND_CHOPPED_PX;
            }

            mDialRadiusPx = mDialDiameterPx / 2;

            switch (mBezelType) {
                case BEZEL_SLIDE_RULE:
                    mClockDialDiameterPx = mDialDiameterPx * mSlideRuleDiameter;
                    break;
                case BEZEL_TACHYMETER:
                    mClockDialDiameterPx = mDialDiameterPx * mTachymeterDiameter;
                    break;
                default:
                    mClockDialDiameterPx = mDialDiameterPx;
            }
            mClockDialRadiusPx = mClockDialDiameterPx / 2;

            mDayTextPaint = new Paint();
            mDayTextPaint.setAntiAlias(true);
            mDayTextPaint.setTextSize(getClockDialTextSizePx(mDayDateTextSizeVmin));
            mDayTextPaint.setColor(Color.BLACK);
            mDayTextPaint.setTextAlign(Paint.Align.CENTER);

            mDateTextPaint = new Paint();
            mDateTextPaint.setAntiAlias(true);
            mDateTextPaint.setTextSize(getClockDialTextSizePx(mDayDateTextSizeVmin));
            mDateTextPaint.setColor(Color.BLACK);
            mDateTextPaint.setTypeface(mTypeface);
            mDateTextPaint.setTextAlign(Paint.Align.CENTER);

            updateDials();
            updateHands();

            initBackgroundBitmap();
            initBackgroundBitmapZoomDayDate();
            initAmbientBackgroundBitmap();

            if (!mAmbient) {
                clearIdle();
            }
        }

        /**
         * Captures tap event (and tap type). The {@link WatchFaceService#TAP_TYPE_TAP} case can be
         * used for implementing specific logic to handle the gesture.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    if (mZoomDayDate) {
                        cancelMultiTap();
                        mZoomDayDate = false;
                    } else {
                        if (mTopSubDial.contains(x, y)) {
                            cancelMultiTap();
                            stopwatchButton1();
                            updateTimer();
                        } else if (mLeftSubDial.contains(x, y)) {
                            cancelMultiTap();
                            stopwatchButton2();
                            updateTimer();
                        } else if (mBottomSubDial.contains(x, y)) {
                            multiTapEvent(MULTI_TAP_TYPE_BOTTOM_SUB_DIAL);
                        } else if (mBatterySubDial.contains(x, y)) {
                            cancelMultiTap();
                            mZoomDayDate = true;
                            updateTimer();
                        } else if (mLeftSubDial.isBelow(y) && mTopSubDial.isToTheRightOf(x)) {
                            cancelMultiTap();
                            mShowVersionNumber = !mShowVersionNumber;
                            mBackgroundBitmap2 = null;
                            invalidate();
                        } else if (isInTapArea(x, y, mSurfaceCenterXPx, mSurfaceCenterYPx)) {
                            multiTapEvent(MULTI_TAP_TYPE_CENTER_OF_DIAL);
                        } else {
                            cancelMultiTap();
                        }
                    }
                    break;
            }
            invalidate();
            if (!mAmbient) {
                clearIdle();
            }
        }

        // BEGIN MULTI-TAP

        private final int TAP_RADIUS_DP = 24;

        private boolean isInTapArea(float x, float y, float cx, float cy) {
            float dx = x - cx;
            float dy = y - cy;
            return dx * dx + dy * dy <= (TAP_RADIUS_DP * TAP_RADIUS_DP * mPixelDensity * mPixelDensity);
        }

        private final int MULTI_TAP_TYPE_CENTER_OF_DIAL = 1;
        private final int MULTI_TAP_TYPE_BOTTOM_SUB_DIAL = 2;

        public void onMultiTapCommand(int type, int numberOfTaps) {
            switch (type) {
                case MULTI_TAP_TYPE_BOTTOM_SUB_DIAL:
                    switch (numberOfTaps) {
                        case 1:
                            if (mEmulatorMode) {
                                mPutChronographSecondsOnSubDial = !mPutChronographSecondsOnSubDial;
                                setUpdateRate();
                            }
                            break;
                        case 2:
                            if (mEmulatorMode) {
                                mDemoTimeMode = !mDemoTimeMode;
                                updateTimer();
                            }
                            break;
                    }
                    break;
                case MULTI_TAP_TYPE_CENTER_OF_DIAL:
                    switch (numberOfTaps) {
                        // FIXME: what to do here?
                    }
                    break;
            }
        }

        private MultiTapHandler mMultiTapHandler = null;

        private void multiTapEvent(int type) {
            if (mMultiTapHandler == null) {
                mMultiTapHandler = new MultiTapHandler(this);
            }
            mMultiTapHandler.onTapEvent(type);
        }

        private void cancelMultiTap() {
            if (mMultiTapHandler != null) {
                mMultiTapHandler.cancel();
            }
        }

        // END MULTI-TAP

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            long now = System.currentTimeMillis();

            if (mDemoTimeMode) {
                mCalendar.set(2019, 5 /* JUN */, 30, 10, 10, 32);
            } else {
                mCalendar.setTimeInMillis(now);
            }

            int dayOfMonth = mCalendar.get(Calendar.DAY_OF_MONTH);
            int dayOfWeek = mCalendar.get(Calendar.DAY_OF_WEEK);

            if ((lastDayOfMonth == -1) || (lastDayOfMonth != dayOfMonth) || (lastDayOfWeek == -1) || (lastDayOfWeek != dayOfWeek)) {
                mBackgroundBitmap2 = null;
                mBackgroundBitmapZoomDayDate2 = null;
                mAmbientBackgroundBitmap2 = null;
            }

            drawBackground(canvas);
            if (mZoomDayDate) {
                canvas.save();
                zoomCanvas(canvas, mDayDateLeftPx, mDayDateRightPx, mDayDateTopPx, mDayDateBottomPx);
            }
            drawBattery(canvas);
            drawTimeAndStopwatch(canvas);
            if (mZoomDayDate) {
                canvas.restore();
            }
            if (!mAmbient) {
                checkIdle();
            }

            lastDayOfMonth = dayOfMonth;
            lastDayOfWeek = dayOfWeek;
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            PilotWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            PilotWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        /**
         * Starts/stops the {@link #mUpdateTimeHandler} timer based on the state of the watch face.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer
         * should only run in active mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !mAmbient;
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = mUpdateRateMs - (timeMs % mUpdateRateMs);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        // @@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@

        private void initColors() {
            mBackgroundColor = ContextCompat.getColor(getApplicationContext(), R.color.background_color);
            mHourHandColor = ContextCompat.getColor(getApplicationContext(), R.color.hour_hand_color);
            mMinuteHandColor = ContextCompat.getColor(getApplicationContext(), R.color.minute_hand_color);
            mSecondHandColor = ContextCompat.getColor(getApplicationContext(), R.color.second_hand_color);
            mTickColor = ContextCompat.getColor(getApplicationContext(), R.color.tick_color);
        }

        private void initMainDial() {
            mMainDial = new WatchDial(this);
            mMainDial.diameterVmin = 1f;
            mMainDial.centerXVmin = 0.0f;
            mMainDial.centerYVmin = 0.0f;
            mMainDial.nonAmbientOnly = false;
            mMainDial.circle1Diameter = 1.00f;
            mMainDial.circle2Diameter = 0.97f;
            mMainDial.circleStrokeWidthVmin = 0.0025f;

//            mMainDial.backgroundStyle = WatchDialBackgroundStyle.RADIAL_RIDGED;

            WatchDialTickSet tickSet1 = new WatchDialTickSet(mMainDial);
            tickSet1.numberOfTicks = 12;
            tickSet1.outerDiameter = 0.97f;
            tickSet1.innerDiameter = 0.91f;
            tickSet1.strokeWidthVmin = 0.02f;
            tickSet1.nonAmbientOnly = false;
            WatchDialTickSet tickSet2 = new WatchDialTickSet(mMainDial);
            tickSet2.numberOfTicks = 60;
            tickSet2.outerDiameter = 1.00f;
            tickSet2.innerDiameter = 0.94f;
            tickSet2.strokeWidthVmin = 0.005f;
            tickSet2.nonAmbientOnly = false;
            WatchDialTickSet tickSet3 = new WatchDialTickSet(mMainDial);
            tickSet3.numberOfTicks = 300;
            tickSet3.outerDiameter = 1.00f;
            tickSet3.innerDiameter = 0.97f;
            tickSet3.strokeWidthVmin = 0.0025f;
            tickSet3.nonAmbientOnly = false;
            tickSet3.excludeTicks(tickSet2);

            mMainDial.addTickSet(tickSet1);
            mMainDial.addTickSet(tickSet2);
            mMainDial.addTickSet(tickSet3);
        }

        /* chronograph tenths of a second */
        private void initTopSubDial() {
            mTopSubDial = new WatchDial(this);
            mTopSubDial.diameterVmin = 0.35f;
            mTopSubDial.centerXVmin = 0f;
            mTopSubDial.centerYVmin = -0.26f;
            mTopSubDial.nonAmbientOnly = true;
            mTopSubDial.circle1Diameter = 1f;
            mTopSubDial.circle2Diameter = 0.90f;
            mTopSubDial.circleStrokeWidthVmin = 0.0025f;
            mTopSubDial.backgroundColor = mBackgroundColor;
            mTopSubDial.backgroundBrightness = -0.2f;
            mTopSubDial.addText(0.0f, "0");
            mTopSubDial.addText(0.2f, "2");
            mTopSubDial.addText(0.4f, "4");
            mTopSubDial.addText(0.6f, "6");
            mTopSubDial.addText(0.8f, "8");

//            mTopSubDial.borderStyle = WatchDialBorderStyle.INSET;
//            mTopSubDial.borderWidthVmin = 0.02f;
//            mTopSubDial.backgroundStyle = WatchDialBackgroundStyle.RADIAL_RIDGED;

            WatchDialTickSet tickSet1 = new WatchDialTickSet(mTopSubDial);
            tickSet1.numberOfTicks = 20;
            tickSet1.outerDiameter = 1f;
            tickSet1.innerDiameter = 0.80f;
            tickSet1.strokeWidthVmin = 0.005f;
            tickSet1.nonAmbientOnly = false;
            WatchDialTickSet tickSet2 = new WatchDialTickSet(mTopSubDial);
            tickSet2.numberOfTicks = 100;
            tickSet2.outerDiameter = 1f;
            tickSet2.innerDiameter = 0.90f;
            tickSet2.strokeWidthVmin = 0.0025f;
            tickSet2.nonAmbientOnly = false;
            tickSet2.excludeTicks(tickSet1);

            mTopSubDial.addTickSet(tickSet1);
            mTopSubDial.addTickSet(tickSet2);
        }

        /* chronograph minutes and hours */
        private void initLeftSubDial() {
            mLeftSubDial = new WatchDial(this);
            mLeftSubDial.diameterVmin = 0.35f;
            mLeftSubDial.centerXVmin = -0.26f;
            mLeftSubDial.centerYVmin = 0f;
            mLeftSubDial.nonAmbientOnly = false;
            mLeftSubDial.circle1Diameter = 1f;
            mLeftSubDial.circle2Diameter = 0.9f;
            mLeftSubDial.circleStrokeWidthVmin = 0.0025f;
            mLeftSubDial.backgroundColor = mBackgroundColor;
            mLeftSubDial.backgroundBrightness = -0.2f;
            mLeftSubDial.addText(0.00f, "12");
            mLeftSubDial.addText(0.25f, "3");
            mLeftSubDial.addText(0.50f, "6");
            mLeftSubDial.addText(0.75f, "9");

//            mLeftSubDial.borderStyle = WatchDialBorderStyle.INSET;
//            mLeftSubDial.borderWidthVmin = 0.02f;
//            mLeftSubDial.backgroundStyle = WatchDialBackgroundStyle.RADIAL_RIDGED;

            WatchDialTickSet tickSet1 = new WatchDialTickSet(mLeftSubDial);
            tickSet1.numberOfTicks = 12;
            tickSet1.outerDiameter = 1f;
            tickSet1.innerDiameter = 0.80f;
            tickSet1.strokeWidthVmin = 0.005f;
            tickSet1.nonAmbientOnly = false;
            WatchDialTickSet tickSet2 = new WatchDialTickSet(mLeftSubDial);
            tickSet2.numberOfTicks = 60;
            tickSet2.outerDiameter = 1f;
            tickSet2.innerDiameter = 0.90f;
            tickSet2.strokeWidthVmin = 0.0025f;
            tickSet2.nonAmbientOnly = false;
            tickSet2.excludeTicks(tickSet1);

            mLeftSubDial.addTickSet(tickSet1);
            mLeftSubDial.addTickSet(tickSet2);
        }

        /* chronograph seconds */
        private void initBottomSubDial() {
            mBottomSubDial = new WatchDial(this);
            mBottomSubDial.diameterVmin = 0.35f;
            mBottomSubDial.centerXVmin = 0f;
            mBottomSubDial.centerYVmin = 0.26f;
            mBottomSubDial.nonAmbientOnly = false;
            mBottomSubDial.circle1Diameter = 1f;
            mBottomSubDial.circle2Diameter = 0.9f;
            mBottomSubDial.circleStrokeWidthVmin = 0.0025f;
            mBottomSubDial.backgroundColor = mBackgroundColor;
            mBottomSubDial.backgroundBrightness = -0.2f;
            mBottomSubDial.addText(0.00f, "60");
            mBottomSubDial.addText(0.25f, "15");
            mBottomSubDial.addText(0.50f, "30");
            mBottomSubDial.addText(0.75f, "45");

//            mBottomSubDial.borderStyle = WatchDialBorderStyle.INSET;
//            mBottomSubDial.borderWidthVmin = 0.02f;
//            mBottomSubDial.backgroundStyle = WatchDialBackgroundStyle.RADIAL_RIDGED;

            WatchDialTickSet tickSet1 = new WatchDialTickSet(mBottomSubDial);
            tickSet1.numberOfTicks = 12;
            tickSet1.outerDiameter = 1f;
            tickSet1.innerDiameter = 0.80f;
            tickSet1.strokeWidthVmin = 0.005f;
            tickSet1.nonAmbientOnly = false;
            WatchDialTickSet tickSet2 = new WatchDialTickSet(mBottomSubDial);
            tickSet2.numberOfTicks = 60;
            tickSet2.outerDiameter = 1f;
            tickSet2.innerDiameter = 0.90f;
            tickSet2.strokeWidthVmin = 0.0025f;
            tickSet2.nonAmbientOnly = false;
            tickSet2.excludeTicks(tickSet1);

            mBottomSubDial.addTickSet(tickSet1);
            mBottomSubDial.addTickSet(tickSet2);
        }

        /* battery percentage */
        private void initBatterySubDial() {
            mBatterySubDial = new WatchDial(this);
            mBatterySubDial.diameterVmin = 0.6f;
            mBatterySubDial.centerXVmin = 0.125f;
            mBatterySubDial.centerYVmin = 0f;
            mBatterySubDial.nonAmbientOnly = false;
            mBatterySubDial.startAngle = 150f;
            mBatterySubDial.endAngle = 30f;
            mBatterySubDial.excludeTicksFrom = 0.4f;
            mBatterySubDial.excludeTicksTo = 0.6f;
            mBatterySubDial.circle1Diameter = 1f;
            mBatterySubDial.circle2Diameter = 0.92f;
            mBatterySubDial.circleStrokeWidthVmin = 0.0025f;
            mBatterySubDial.addText(0.00f, "0%");
            mBatterySubDial.addText(1.00f, "100%");
            mBatterySubDial.typeface = mCondensedTypeface;

            // mBatterySubDial.textDirection = WatchDialTextDirection.TEXT_DIRECTION_RADIAL;

            WatchDialTickSet tickSet1 = new WatchDialTickSet(mBatterySubDial);
            tickSet1.numberOfTicks = 2;
            tickSet1.outerDiameter = 1f;
            tickSet1.innerDiameter = 0.80f;
            tickSet1.strokeWidthVmin = 0.01f;
            tickSet1.nonAmbientOnly = false;
            WatchDialTickSet tickSet2 = new WatchDialTickSet(mBatterySubDial);
            tickSet2.numberOfTicks = 10;
            tickSet2.outerDiameter = 1f;
            tickSet2.innerDiameter = 0.86f;
            tickSet2.strokeWidthVmin = 0.005f;
            tickSet2.nonAmbientOnly = false;
            tickSet2.excludeTicks(tickSet1);
            WatchDialTickSet tickSet3 = new WatchDialTickSet(mBatterySubDial);
            tickSet3.numberOfTicks = 20;
            tickSet3.outerDiameter = 1f;
            tickSet3.innerDiameter = 0.92f;
            tickSet3.strokeWidthVmin = 0.0025f;
            tickSet3.nonAmbientOnly = false;
            tickSet3.excludeTicks(tickSet1);
            tickSet3.excludeTicks(tickSet2);

            mBatterySubDial.addTickSet(tickSet1);
            mBatterySubDial.addTickSet(tickSet2);
            mBatterySubDial.addTickSet(tickSet3);
        }

        private void initDials() {
            initMainDial();
            initTopSubDial();
            initLeftSubDial();
            initBottomSubDial();
            initBatterySubDial();
        }

        private void initHands() {
            mChronographSecondFractionHand = new WatchHand(mTopSubDial);
            mChronographSecondFractionHand.color = mSecondHandColor;
            mChronographSecondFractionHand.nonAmbientOnly = true;
            mChronographSecondFractionHand.lengthPctRadius = 0.9f;
            mChronographSecondFractionHand.lengthBehindPctRadius = 0.225f;
            mChronographSecondFractionHand.widthVmin = 0.01f;
            mChronographSecondFractionHand.shadowRadiusPx = 2f;

            mSubdialSecondHand = new WatchHand(mBottomSubDial);
            mSubdialSecondHand.color = mSecondHandColor;
            mSubdialSecondHand.nonAmbientOnly = false; /* can draw in ambient in certain situations */
            mSubdialSecondHand.lengthPctRadius = 0.9f;
            mSubdialSecondHand.lengthBehindPctRadius = 0.225f;
            mSubdialSecondHand.widthVmin = 0.01f;
            mSubdialSecondHand.shadowRadiusPx = 2f;

            mChronographMinuteHand = new WatchHand(mLeftSubDial);
            mChronographMinuteHand.color = mMinuteHandColor;
            mChronographMinuteHand.nonAmbientOnly = false; /* can draw in ambient in certain situations */
            mChronographMinuteHand.hasArrowHead = true;
            mChronographMinuteHand.lengthPctRadius = 0.8f;
            mChronographMinuteHand.widthVmin = 0.01f;
            mChronographMinuteHand.shadowRadiusPx = 3f;

            mChronographHourHand = new WatchHand(mLeftSubDial);
            mChronographHourHand.color = mHourHandColor;
            mChronographHourHand.nonAmbientOnly = false; /* can draw in ambient in certain situations */
            mChronographHourHand.hasArrowHead = true;
            mChronographHourHand.lengthPctRadius = 0.8f * 0.6f;
            mChronographHourHand.widthVmin = 0.01f;
            mChronographHourHand.shadowRadiusPx = 2f;

            mSecondHand = new WatchHand(mMainDial);
            mSecondHand.color = mSecondHandColor;
            mSecondHand.nonAmbientOnly = false; /* can draw in ambient in certain situations */
            mSecondHand.lengthPctRadius = 0.95f;
            mSecondHand.lengthBehindPctRadius = 0.25f;
            mSecondHand.widthVmin = 0.01f;
            mSecondHand.shadowRadiusPx = 6f;

            mMinuteHand = new WatchHand(mMainDial);
            mMinuteHand.color = mMinuteHandColor;
            mMinuteHand.nonAmbientOnly = false;
            mMinuteHand.hasArrowHead = true;
            mMinuteHand.lengthPctRadius = 0.9f;
            mMinuteHand.widthVmin = 0.02f;
            mMinuteHand.shadowRadiusPx = 5f;

            mHourHand = new WatchHand(mMainDial);
            mHourHand.color = mHourHandColor;
            mHourHand.nonAmbientOnly = false;
            mHourHand.hasArrowHead = true;
            mHourHand.lengthPctRadius = 0.9f * 0.6f;
            mHourHand.widthVmin = 0.02f;
            mHourHand.shadowRadiusPx = 4f;

            mBatteryHand = new WatchHand(mBatterySubDial);
            mBatteryHand.color = mSecondHandColor;
            mBatteryHand.nonAmbientOnly = false;
            mBatteryHand.hasArrowHead = true;
            mBatteryHand.lengthPctRadius = 0.9f;
            mBatteryHand.lengthBehindPctRadius = 0.225f;
            mBatteryHand.widthVmin = 0.01f;
            mBatteryHand.shadowRadiusPx = 2f;
        }

        private void updateDials() {
            mMainDial.update();
            mTopSubDial.update();
            mLeftSubDial.update();
            mBottomSubDial.update();
            mBatterySubDial.update();
        }

        private void updateHands() {
            mHourHand.update();
            mMinuteHand.update();
            mSecondHand.update();
            mBatteryHand.update();
            mChronographHourHand.update();
            mChronographMinuteHand.update();
            mSubdialSecondHand.update();
            mChronographSecondFractionHand.update();
        }

        private void setUpdateRate() {
            if (mPutChronographSecondsOnSubDial) {
                // chrono seconds on subdial; time seconds on main dial
                if (mStopwatchRunning) {
                    mUpdateRateMs = 50; // for refreshing fraction of second
                } else {
                    mUpdateRateMs = 200;
                }
            } else {
                // time seconds on subdial; chrono seconds on main dial
                if (mStopwatchRunning) {
                    mUpdateRateMs = 50; // for refreshing fraction of second
                } else {
                    mUpdateRateMs = 200;
                }
            }
        }

        private void drawBezel(Canvas canvas, boolean ambient) {
            switch (mBezelType) {
                case BEZEL_SLIDE_RULE:
                    drawSlideRuleBezel(canvas, ambient);
                    break;
                case BEZEL_TACHYMETER:
                    drawTachymeterBezel(canvas, ambient);
                    break;
            }
        }

        private float slideRuleDegrees(float x) {
            return MoreMath.mod((float) Math.log10(x), 1.0f) * 360f;
        }

        private void drawSlideRuleTick(Canvas canvas, boolean ambient, float x, float y, Paint paint) {
            float degrees = slideRuleDegrees(x);
            canvas.save();
            canvas.rotate(degrees, mSurfaceCenterXPx, mSurfaceCenterYPx);

            float yc = mSurfaceCenterYPx - mDialRadiusPx * (1 + mSlideRuleDiameter) / 2;
            float y1 = yc + mDialRadiusPx * y * (1 - mSlideRuleDiameter) / 2;
            float y2 = yc - mDialRadiusPx * y * (1 - mSlideRuleDiameter) / 2;

            canvas.drawLine(
                    mSurfaceCenterXPx, y1,
                    mSurfaceCenterXPx, y2,
                    paint);
            canvas.restore();
        }

        private float tachymeterDegrees(float x) {
            /* if x is 60, return [360] 0 */
            /* if x is 240, return 90 */
            /* if x is 120, return 180 */

            return MoreMath.mod(60f / x, 1) * 360f;
        }

        private void drawTachymeterTick(Canvas canvas, boolean ambient, float x, float y, Paint paint) {
            float degrees = tachymeterDegrees(x);
            canvas.save();
            canvas.rotate(degrees, mSurfaceCenterXPx, mSurfaceCenterYPx);

            float y1 = mSurfaceCenterYPx - mDialRadiusPx * mTachymeterDiameter;
            float y2 = y1 - mDialRadiusPx * y * (1 - mTachymeterDiameter);

            canvas.drawLine(
                    mSurfaceCenterXPx, y1,
                    mSurfaceCenterXPx, y2,
                    paint);
            canvas.restore();
        }

        private void drawSlideRuleBezel(Canvas canvas, boolean ambient) {
            Paint paint = new Paint();
            paint.setAntiAlias(true);
            paint.setColor(ambient ? Color.WHITE : mTickColor);
            paint.setStrokeWidth(MINIMUM_STROKE_WIDTH_PX);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.BUTT);

            canvas.drawCircle(mSurfaceCenterXPx, mSurfaceCenterYPx, mDialRadiusPx, paint);
            canvas.drawCircle(mSurfaceCenterXPx, mSurfaceCenterYPx, mDialRadiusPx * (1 + mSlideRuleDiameter) / 2, paint);
            canvas.drawCircle(mSurfaceCenterXPx, mSurfaceCenterYPx, mDialRadiusPx * mSlideRuleDiameter, paint);

            int i;
            for (i = 1000; i < 2500; i += 100) {
                drawSlideRuleTick(canvas, ambient, i, 0.5f, paint);
            }
            for (i = 1000; i < 2500; i += 20) {
                drawSlideRuleTick(canvas, ambient, i, 0.25f, paint);
            }
            for (i = 2500; i < 5000; i += 250) {
                drawSlideRuleTick(canvas, ambient, i, 0.5f, paint);
            }
            for (i = 2500; i < 5000; i += 50) {
                drawSlideRuleTick(canvas, ambient, i, 0.25f, paint);
            }
            for (i = 5000; i < 10000; i += 500) {
                drawSlideRuleTick(canvas, ambient, i, 0.5f, paint);
            }
            for (i = 5000; i < 10000; i += 100) {
                drawSlideRuleTick(canvas, ambient, i, 0.25f, paint);
            }

            Paint textPaint = new Paint();
            textPaint.setAntiAlias(true);
            textPaint.setColor(ambient ? Color.WHITE : mTickColor);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(getClockDialTextSizePx(mSlideRuleTextSizeVmin));
            textPaint.setStyle(Paint.Style.FILL);
            textPaint.setTypeface(mTypeface);

            int[] slideRulePoints = {10, 11, 12, 15, 18, 20, 25, 30, 35, 40, 45, 50, 55, 60, 70, 80, 90};
            for (int point : slideRulePoints) {
                drawSlideRuleText(canvas, ambient, point, Integer.toString(point), SlideRuleDial.SLIDE_RULE_DIAL_INNER, textPaint);
                drawSlideRuleText(canvas, ambient, point, Integer.toString(point), SlideRuleDial.SLIDE_RULE_DIAL_OUTER, textPaint);
            }
        }

        private void drawSlideRuleText(Canvas canvas, boolean ambient,
                                       float x, String text,
                                       SlideRuleDial slideRuleDial,
                                       Paint textPaint) {
            float degrees = slideRuleDegrees(x);
            float radiusPx = mDialRadiusPx;
            switch (slideRuleDial) {
                case SLIDE_RULE_DIAL_INNER:
                    radiusPx = mDialRadiusPx * 0.85f;
                    break;
                case SLIDE_RULE_DIAL_OUTER:
                    radiusPx = mDialRadiusPx * 0.95f;
                    break;
            }
            drawRoundDialText(canvas, ambient, degrees, text, radiusPx, textPaint);
        }

        private void drawTachymeterText(Canvas canvas, boolean ambient, float x, String text, Paint textPaint) {
            float degrees = tachymeterDegrees(x);
            drawRoundDialText(canvas, ambient, degrees, text, mDialRadiusPx * 0.95f, textPaint);
        }

        private void drawRoundDialText(Canvas canvas, boolean ambient,
                                       float degrees, String text, float radiusPx,
                                       Paint textPaint) {
            canvas.save();
            canvas.rotate(degrees, mSurfaceCenterXPx, mSurfaceCenterYPx);
            float textXPx = mSurfaceCenterXPx;
            float textYPx = mSurfaceCenterYPx - radiusPx;
            if (degrees >= (90f + TEXT_ROTATION_FUDGE_FACTOR) &&
                    degrees <= (270f - TEXT_ROTATION_FUDGE_FACTOR)) {
                canvas.rotate(180f, textXPx, textYPx);
            }
            drawVerticallyCenteredText(canvas, text, textXPx, textYPx, textPaint);
            canvas.restore();
        }

        private void drawTachymeterBezel(Canvas canvas, boolean ambient) {
            Paint paint = new Paint();
            paint.setAntiAlias(true);
            paint.setColor(ambient ? Color.WHITE : mTickColor);
            paint.setStrokeWidth(MINIMUM_STROKE_WIDTH_PX);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.BUTT);

            canvas.drawCircle(mSurfaceCenterXPx, mSurfaceCenterYPx, mDialRadiusPx, paint);
            canvas.drawCircle(mSurfaceCenterXPx, mSurfaceCenterYPx, mDialRadiusPx * mTachymeterDiameter, paint);

            if (false) {
                int i;
                for (i = 60; i <= 80; i += 1) {
                    drawTachymeterTick(canvas, ambient, i, 0.5f, paint);
                }
                for (i = 80; i <= 120; i += 1) {
                    drawTachymeterTick(canvas, ambient, i, 0.5f, paint);
                }
                drawTachymeterTick(canvas, ambient, 135, 0.5f, paint);
                drawTachymeterTick(canvas, ambient, 150, 0.5f, paint);
                drawTachymeterTick(canvas, ambient, 175, 0.5f, paint);
                drawTachymeterTick(canvas, ambient, 200, 0.5f, paint);
                drawTachymeterTick(canvas, ambient, 250, 0.5f, paint);
                drawTachymeterTick(canvas, ambient, 300, 0.5f, paint);
                drawTachymeterTick(canvas, ambient, 400, 0.5f, paint);
                drawTachymeterTick(canvas, ambient, 500, 0.5f, paint);
                drawTachymeterTick(canvas, ambient, 600, 0.5f, paint);
                drawTachymeterTick(canvas, ambient, 750, 0.5f, paint);
                drawTachymeterTick(canvas, ambient, 1000, 0.5f, paint);
            }

            Paint textPaint = new Paint();
            textPaint.setAntiAlias(true);
            textPaint.setColor(ambient ? Color.WHITE : mTickColor);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(getClockDialTextSizePx(mSlideRuleTextSizeVmin));
            textPaint.setStyle(Paint.Style.FILL);
            textPaint.setTypeface(mTypeface);

            int[] points = {
                    60, 62, 64, 66, 68, 70, 72, 75, 80, 85, 90, 100, 110, 120,
                    135, 150, 175, 200, 240, 300, 400, 600, 1000
            };

            for (int point : points) {
                drawTachymeterText(canvas, ambient, point, Integer.toString(point), textPaint);
            }
        }

        /**
         * Draws clock dial without "Pilot Watch 3000" text, version number text, or day/date.
         */
        private void initBackgroundBitmap() {
            mBackgroundBitmap = Bitmap.createBitmap(mSurfaceWidthPx, mSurfaceHeightPx, Bitmap.Config.ARGB_8888);
            Canvas backgroundCanvas = new Canvas(mBackgroundBitmap);
            drawClockDial(backgroundCanvas, false);
            mMainDial.draw(backgroundCanvas, false);
            mTopSubDial.draw(backgroundCanvas, false);
            mLeftSubDial.draw(backgroundCanvas, false);
            mBottomSubDial.draw(backgroundCanvas, false);
            mBatterySubDial.draw(backgroundCanvas, false);
            drawBezel(backgroundCanvas, false);
            mBackgroundBitmap2 = null;
        }

        /**
         * Draws zoomed-in clock dial without day/date.
         */
        private void initBackgroundBitmapZoomDayDate() {
            mBackgroundBitmapZoomDayDate = Bitmap.createBitmap(mSurfaceWidthPx, mSurfaceHeightPx, Bitmap.Config.ARGB_8888);
            Canvas backgroundCanvas = new Canvas(mBackgroundBitmapZoomDayDate);
            zoomCanvas(backgroundCanvas, mDayDateLeftPx, mDayDateRightPx, mDayDateTopPx, mDayDateBottomPx);
            drawClockDial(backgroundCanvas, false);
            mMainDial.draw(backgroundCanvas, false);
            mTopSubDial.draw(backgroundCanvas, false);
            mLeftSubDial.draw(backgroundCanvas, false);
            mBottomSubDial.draw(backgroundCanvas, false);
            mBatterySubDial.draw(backgroundCanvas, false);
            drawBezel(backgroundCanvas, false);
            mBackgroundBitmapZoomDayDate2 = null;
        }

        /**
         * Draws clock dial without "Pilot Watch 3000" text, version number text, or day/date.
         */
        private void initAmbientBackgroundBitmap() {
            mAmbientBackgroundBitmap = Bitmap.createBitmap(mSurfaceWidthPx, mSurfaceHeightPx, Bitmap.Config.ARGB_8888);
            Canvas backgroundCanvas = new Canvas(mAmbientBackgroundBitmap);
            drawClockDial(backgroundCanvas, true);
            mMainDial.draw(backgroundCanvas, true);
//                mTopSubDial.draw(backgroundCanvas, true);
//                mLeftSubDial.draw(backgroundCanvas, true);
//                mBottomSubDial.draw(backgroundCanvas, true);
            mBatterySubDial.draw(backgroundCanvas, true);
            drawBezel(backgroundCanvas, true);
            mAmbientBackgroundBitmap2 = null;
        }

        /**
         * adds "Pilot Watch 3000" or version number text, and day/date.
         */
        private void initBackgroundBitmap2() {
            if (mBackgroundBitmap2 != null) {
                return;
            }
            mBackgroundBitmap2 = Bitmap.createBitmap(mBackgroundBitmap);
            Canvas backgroundCanvas = new Canvas(mBackgroundBitmap2);
            drawDate(backgroundCanvas, false);
            drawWatchFaceName(backgroundCanvas, false);
        }

        /**
         * adds day/date.
         */
        private void initBackgroundBitmapZoomDayDate2() {
            if (mBackgroundBitmapZoomDayDate2 != null) {
                return;
            }
            mBackgroundBitmapZoomDayDate2 = Bitmap.createBitmap(mBackgroundBitmapZoomDayDate);
            Canvas backgroundCanvas = new Canvas(mBackgroundBitmapZoomDayDate2);
            zoomCanvas(backgroundCanvas, mDayDateLeftPx, mDayDateRightPx, mDayDateTopPx, mDayDateBottomPx);
            drawDate(backgroundCanvas, false);
        }

        /**
         * adds "Pilot Watch 3000" or version number text, and day/date.
         */
        private void initAmbientBackgroundBitmap2() {
            if (mAmbientBackgroundBitmap2 != null) {
                return;
            }
            mAmbientBackgroundBitmap2 = Bitmap.createBitmap(mAmbientBackgroundBitmap);
            Canvas backgroundCanvas = new Canvas(mAmbientBackgroundBitmap2);
            drawDate(backgroundCanvas, true);
            drawWatchFaceName(backgroundCanvas, true);
        }

        private HashMap<String, Integer> mDayFontStretchMap = new HashMap<String, Integer>();

        private void drawClockDial(Canvas canvas, boolean ambient) {
            canvas.drawColor(Color.WHITE);

            int widthPx = canvas.getWidth();
            int heightPx = canvas.getHeight();

            Rect dayBounds = new Rect();
            Rect dateBounds = new Rect();

            Rect maxDayBounds = new Rect();
            Rect maxDateBounds = new Rect();

            Map<String, Integer> dayMap = mCalendar.getDisplayNames(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.getDefault());
            mDayTextPaint.setTypeface(mCondensedTypeface);
            for (String dayText : dayMap.keySet()) {
                dayText = dayText.toUpperCase();
                mDayTextPaint.getTextBounds(dayText, 0, dayText.length(), dayBounds);
                maxDayBounds.left = Math.min(maxDayBounds.left, dayBounds.left);
                maxDayBounds.right = Math.max(maxDayBounds.right, dayBounds.right);
                maxDayBounds.top = Math.min(maxDayBounds.top, dayBounds.top);
                maxDayBounds.bottom = Math.max(maxDayBounds.bottom, dayBounds.bottom);
            }

            mDayTextPaint.setTypeface(mTypeface);
            for (String dayText : dayMap.keySet()) {
                dayText = dayText.toUpperCase();
                mDayTextPaint.getTextBounds(dayText, 0, dayText.length(), dayBounds);
                if (dayBounds.width() <= maxDayBounds.width()) {
                    mDayFontStretchMap.put(dayText, 0);
                } else {
                    mDayFontStretchMap.put(dayText, -1);
                }
            }

            for (int date = 1; date <= 31; date += 1) {
                String dateText = Integer.toString(date);
                mDateTextPaint.getTextBounds(dateText, 0, dateText.length(), dateBounds);
                maxDateBounds.left = Math.min(maxDateBounds.left, dateBounds.left);
                maxDateBounds.right = Math.max(maxDateBounds.right, dateBounds.right);
                maxDateBounds.top = Math.min(maxDateBounds.top, dateBounds.top);
                maxDateBounds.bottom = Math.max(maxDateBounds.bottom, dateBounds.bottom);
            }

            mDayDateTextSizePx = getClockDialTextSizePx(mDayDateTextSizeVmin);

            /* 1 to 31, outer */
            float dateWindowRightXPx = mSurfaceCenterXPx + mClockDialRadiusPx * mDayDateOuterVmin;
            float dateWindowLeftXPx = dateWindowRightXPx - maxDateBounds.width() - mClockDialDiameterPx * 0.02f;

            /* SUN to SAT, inner */
            float dayWindowRightXPx = dateWindowLeftXPx - mClockDialDiameterPx * 0.01f;
            float dayWindowLeftXPx = dayWindowRightXPx - maxDayBounds.width() - mClockDialDiameterPx * 0.02f;

            mDayWindowCenterXPx = (dayWindowLeftXPx + dayWindowRightXPx) / 2f;
            mDateWindowCenterXPx = (dateWindowLeftXPx + dateWindowRightXPx) / 2f;

            mDayDateTopPx = mSurfaceCenterYPx - mDayDateTextSizePx * 0.5f;
            mDayDateBottomPx = mSurfaceCenterYPx + mDayDateTextSizePx * 0.5f;
            mDayDateLeftPx = dayWindowLeftXPx;
            mDayDateRightPx = dateWindowRightXPx;

            Path dialPath = new Path();
            dialPath.addRect(0, 0, widthPx, heightPx, Path.Direction.CW);

            Path dateWindowPath = new Path();
            dateWindowPath.addRect(
                    dateWindowLeftXPx, mDayDateTopPx,
                    dateWindowRightXPx, mDayDateBottomPx, Path.Direction.CW
            );

            Path dayWindowPath = new Path();
            dayWindowPath.addRect(
                    dayWindowLeftXPx, mDayDateTopPx,
                    dayWindowRightXPx, mDayDateBottomPx, Path.Direction.CW
            );

            dialPath.op(dateWindowPath, Path.Op.DIFFERENCE);
            dialPath.op(dayWindowPath, Path.Op.DIFFERENCE);

            Paint backgroundPaint = new Paint();
            backgroundPaint.setAntiAlias(true);
            if (ambient) {
                backgroundPaint.setColor(Color.BLACK);
            } else {
                backgroundPaint.setColor(mBackgroundColor);
                backgroundPaint.setShadowLayer(2, 0, 0, Color.BLACK);
            }
            backgroundPaint.setStyle(Paint.Style.FILL);
            canvas.drawPath(dialPath, backgroundPaint);
        }

        private void drawWatchFaceName(Canvas canvas, boolean ambient) {
            drawWatchFaceName(canvas, ambient, true);
            drawWatchFaceName(canvas, ambient, false);
        }

        private void drawWatchFaceName(Canvas canvas, boolean ambient, boolean isShadow) {
            if (isShadow && ambient) {
                return;
            }
            if (mShowVersionNumber) {
                drawWatchFaceVersionTextArcs(canvas, ambient, isShadow);
            } else {
                drawWatchFaceNameTextArcs(canvas, ambient, isShadow);
            }
        }

        private Paint getWatchFaceNameTextPaint(boolean ambient, boolean isShadow) {
            Paint textPaint = new Paint();
            textPaint.setAntiAlias(true);
            if (ambient) {
                textPaint.setColor(Color.WHITE);
            } else {
                if (isShadow) {
                    textPaint.setColor(Color.BLACK);
                } else {
                    textPaint.setColor(mTickColor);
                }
            }
            textPaint.setTextSize(getClockDialTextSizePx(mWatchFaceNameTextSizeVmin));
            textPaint.setTypeface(mTypeface);
            return textPaint;
        }

        private void drawWatchFaceNameText(Canvas canvas, boolean ambient, boolean isShadow) {
            float dx = isShadow ? 0f : 0f;
            float dy = isShadow ? 1f : 0f;
            float lineSpacingPx = getClockDialTextSizePx(mWatchFaceNameTextSizeVmin);
            Paint textPaint = getWatchFaceNameTextPaint(ambient, isShadow);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setLetterSpacing(lineSpacingPx * 0.005f);
            float xPx = mSurfaceCenterXPx - mClockDialDiameterPx * mWatchFaceNameLeftOffsetVmin + dx;
            if (ambient && mTopSubDial.nonAmbientOnly) {
                xPx = mSurfaceCenterXPx + dx;
            }
            float yPx = mSurfaceCenterYPx - mClockDialDiameterPx * mWatchFaceNameTopOffsetVmin -
                    (1f - TEXT_CAP_HEIGHT / 2) * lineSpacingPx + dy;
            canvas.drawText("PILOT", xPx, yPx, textPaint);
            yPx += lineSpacingPx;
            canvas.drawText("WATCH", xPx, yPx, textPaint);
            yPx += lineSpacingPx;
            canvas.drawText("3000", xPx, yPx, textPaint);
        }

        private void drawWatchFaceVersionText(Canvas canvas, boolean ambient, boolean isShadow) {
            try {
                PackageInfo pInfo = getApplicationContext().getPackageManager().getPackageInfo(getPackageName(), 0);

                float dx = isShadow ? 0f : 0f;
                float dy = isShadow ? 1f : 0f;
                float lineSpacingPx = getClockDialTextSizePx(mWatchFaceNameTextSizeVmin);
                Paint textPaint = getWatchFaceNameTextPaint(ambient, isShadow);
                textPaint.setTextAlign(Paint.Align.RIGHT);
                textPaint.setLetterSpacing(lineSpacingPx * 0.005f);
                float paddingPx = getPaddingPx();
                float xPx = mSurfaceCenterXPx - mTopSubDial.radiusPx - getPaddingPx() + dx;
                if (ambient && mTopSubDial.nonAmbientOnly) {
                    xPx = mSurfaceCenterXPx + dx;
                }
                float yPx = mSurfaceCenterYPx - mClockDialDiameterPx * mWatchFaceNameTopOffsetVmin +
                        (TEXT_CAP_HEIGHT / 2) * lineSpacingPx + dy;
                canvas.drawText(pInfo.versionName, xPx, yPx, textPaint);
                yPx += lineSpacingPx;
                canvas.drawText("(" + pInfo.versionCode + ")", xPx, yPx, textPaint);
            } catch (Exception e) {
                drawWatchFaceNameText(canvas, ambient, isShadow);
            }
        }

        private void drawTextUpperLeftArc(Canvas canvas, boolean ambient, boolean isShadow, String text) {
            drawTextArc(canvas, ambient, isShadow, text, 180f, 90f);
        }

        private void drawTextUpperRightArc(Canvas canvas, boolean ambient, boolean isShadow, String text) {
            drawTextArc(canvas, ambient, isShadow, text, 270f, 72f);
        }

        private void drawTextArc(Canvas canvas, boolean ambient, boolean isShadow, String text, float startAngle, float sweepAngle) {
            float dx = isShadow ? 0f : 0f;
            float dy = isShadow ? 1f : 0f;
            float lineSpacingPx = getClockDialTextSizePx(mWatchFaceNameTextSizeVmin);
            Paint textPaint = getWatchFaceNameTextPaint(ambient, isShadow);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setLetterSpacing(lineSpacingPx * 0.005f);
            float baselineRadiusPx = mClockDialRadiusPx * mMainDial.ticksInner() - getPaddingPx() - textPaint.getTextSize() * 0.7f;

            Path path = new Path();
            path.addArc(
                    mSurfaceCenterXPx - baselineRadiusPx + dx,
                    mSurfaceCenterYPx - baselineRadiusPx + dy,
                    mSurfaceCenterXPx + baselineRadiusPx + dx,
                    mSurfaceCenterYPx + baselineRadiusPx + dy,
                    startAngle, sweepAngle
            );
            canvas.drawTextOnPath(text, path, 0f, 0f, textPaint);

        }

        private void drawWatchFaceNameTextArcs(Canvas canvas, Boolean ambient, boolean isShadow) {
            drawTextUpperLeftArc(canvas, ambient, isShadow, "PILOT WATCH");
            drawTextUpperRightArc(canvas, ambient, isShadow, "3000");
        }

        private void drawWatchFaceVersionTextArcs(Canvas canvas, Boolean ambient, boolean isShadow) {
            try {
                PackageInfo pInfo = getApplicationContext().getPackageManager().getPackageInfo(getPackageName(), 0);
                String versionNameText = pInfo.versionName;
                String versionCodeText = "(" + pInfo.versionCode + ")";

                drawTextUpperLeftArc(canvas, ambient, isShadow, versionNameText);
                drawTextUpperRightArc(canvas, ambient, isShadow, versionCodeText);
            } catch (Exception e) {
                // do nothing
            }
        }

        private static final float PADDING_DP = 4;

        private float getPaddingPx() {
            return PADDING_DP * mPixelDensity;
        }

        private float dpToPx(float dp) {
            return dp * mPixelDensity;
        }

        private int lastDayOfMonth = -1;
        private int lastDayOfWeek = -1;

        private void drawBackground(Canvas canvas) {
            if (mAmbient) {
                initAmbientBackgroundBitmap2();
                canvas.drawBitmap(mAmbientBackgroundBitmap2, 0, 0, null);
            } else if (mZoomDayDate) {
                initBackgroundBitmapZoomDayDate2();
                canvas.drawBitmap(mBackgroundBitmapZoomDayDate2, 0, 0, null);
            } else {
                initBackgroundBitmap2();
                canvas.drawBitmap(mBackgroundBitmap2, 0, 0, null);
            }
        }

        private void drawDate(Canvas canvas) {
            drawDate(canvas, mAmbient);
        }

        private void drawDate(Canvas canvas, boolean ambient) {
            int day = mCalendar.get(Calendar.DAY_OF_WEEK);
            int date = mCalendar.get(Calendar.DAY_OF_MONTH);

            String dayText = mCalendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.getDefault());
            dayText = dayText.toUpperCase();
            String dateText = Integer.toString(date);

            float baselineY = mSurfaceCenterXPx + mDayDateTextSizePx * TEXT_CAP_HEIGHT / 2;

            Integer fontStretch = mDayFontStretchMap.get(dayText);
            if (fontStretch == null) {
                fontStretch = -1;
            }
            if (fontStretch <= -1) {
                mDayTextPaint.setTypeface(mCondensedTypeface);
            } else {
                mDayTextPaint.setTypeface(mTypeface);
            }
            canvas.drawText(dayText, mDayWindowCenterXPx, baselineY, mDayTextPaint);
            canvas.drawText(dateText, mDateWindowCenterXPx, baselineY, mDateTextPaint);
        }

        private void drawBattery(Canvas canvas) {
            float batteryPercentage = -1f;
            IntentFilter intentFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = PilotWatchFace.this.registerReceiver(null, intentFilter);
            if (batteryStatus != null) {
                int batteryLevel = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int batteryScale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                batteryPercentage = batteryLevel * 100f / batteryScale;
            }

            /* apperance of levels off the odometer range in case they happen */
            if (batteryPercentage < 0f) {
                batteryPercentage = -10f;
            } else if (batteryPercentage > 100f) {
                batteryPercentage = 110f;
            }

            float batteryRotation = batteryPercentage / 100f;
            mBatteryHand.draw(canvas, batteryRotation);
        }

        private void drawTimeAndStopwatch(Canvas canvas) {
            WatchHand chronographSecondHand = mPutChronographSecondsOnSubDial ? mSubdialSecondHand : mSecondHand;
            WatchHand wallTimeSecondHand = mPutChronographSecondsOnSubDial ? mSecondHand : mSubdialSecondHand;
            boolean showChronograph = !mAmbient;
            boolean showSecondHand = !mAmbient;

            int h = mCalendar.get(Calendar.HOUR);
            int m = mCalendar.get(Calendar.MINUTE);
            int s = mCalendar.get(Calendar.SECOND);
            int ms = mCalendar.get(Calendar.MILLISECOND); /* 0 to 999 */

            /* when stopwatch is running, watch face refreshes more often.
               However, we still want to only "tick" the time second hand
               5 times a second. */
            int watchMs = (ms / 200) * 200;

            final float seconds = (float) s + (float) watchMs / 1000f; /* [0f, 60f) */
            final float minutes = (float) m + seconds / 60f;      /* [0f, 60f) */
            final float hours = (float) h + minutes / 60f;        /* [0f, 12f) */

            final float secondHandDegrees = seconds / 60f;
            final float minuteHandDegrees = minutes / 60f;
            final float hourHandDegrees = hours / 12f;

            long chronographMs = 0;
            float chronographSecondFractionHandDegrees = 0;
            float chronographSecondHandDegrees = 0;
            float chronographMinuteHandDegrees = 0;
            float chronographHourHandDegrees = 0;

            if (showChronograph) {
                chronographMs = getStopwatchTimeMs();
                chronographMs = (chronographMs / 10) * 10; // resolution 1/100 sec
                if (mDemoTimeMode) {
                    chronographMs = 650 + 1000 * (32 + 60 * (10 + (60 * 10)));
                }
                chronographSecondFractionHandDegrees = (chronographMs % 1000) / 1000f;

                // whether chronograph seconds are on the subdial or the main dial,
                // we change them once a second.
                chronographMs = (long) (chronographMs / 1000) * 1000;

                chronographSecondHandDegrees = (chronographMs % 60000) / 60000f;
                chronographMinuteHandDegrees = (chronographMs % 3600000) / 3600000f;
                chronographHourHandDegrees = (chronographMs % 43200000) / 43200000f;
                mChronographHourHand.draw(canvas, chronographHourHandDegrees);
                mChronographMinuteHand.draw(canvas, chronographMinuteHandDegrees);
                mChronographSecondFractionHand.draw(canvas, chronographSecondFractionHandDegrees);
            }

            // draw whichever is the subdial seconds first
            if (mPutChronographSecondsOnSubDial) {
                if (showChronograph) {
                    chronographSecondHand.draw(canvas, chronographSecondHandDegrees);
                }
            } else {
                if (showSecondHand) {
                    wallTimeSecondHand.draw(canvas, secondHandDegrees);
                }
            }

            // then draw these hands
            mHourHand.draw(canvas, hourHandDegrees);
            mMinuteHand.draw(canvas, minuteHandDegrees);

            // then draw whichever is used for the main dial seconds
            if (mPutChronographSecondsOnSubDial) {
                if (showSecondHand) {
                    wallTimeSecondHand.draw(canvas, secondHandDegrees);
                }
            } else {
                if (showChronograph) {
                    chronographSecondHand.draw(canvas, chronographSecondHandDegrees);
                }
            }
        }

        private void stopwatchButton1() {
            if (mStopwatchRunning) {
                pauseStopwatch();
            } else {
                startStopwatch();
            }
        }

        private void stopwatchButton2() {
            if (!mStopwatchRunning) {
                resetStopwatch();
            }
        }

        private void startStopwatch() {
            if (!mStopwatchRunning) {
                mStopwatchStartTimeMs = System.currentTimeMillis();
            }
            mStopwatchRunning = true;
            mStopwatchPaused = false;
            setUpdateRate();
        }

        private void pauseStopwatch() {
            if (mStopwatchRunning) {
                mStopwatchTimeMs += System.currentTimeMillis() - mStopwatchStartTimeMs;
            }
            mStopwatchRunning = false;
            mStopwatchPaused = true;
            setUpdateRate();
        }

        private void resetStopwatch() {
            mStopwatchRunning = false;
            mStopwatchPaused = false;
            mStopwatchStartTimeMs = 0;
            mStopwatchTimeMs = 0;
            setUpdateRate();
        }

        private long getStopwatchTimeMs() {
            if (mStopwatchRunning) {
                return System.currentTimeMillis() - mStopwatchStartTimeMs + mStopwatchTimeMs;
            }
            return mStopwatchTimeMs;
        }

        /**
         * Ambient refresh rate.  If 0, system handles ambient refreshes.
         */
        private int mAmbientUpdateRateSeconds = 10;

        private static final String AMBIENT_UPDATE_ACTION = "com.webonastick.watchface.pilotwatch.action.AMBIENT_UPDATE";
        private Intent mAmbientUpdateIntent = null;
        private PendingIntent mAmbientUpdatePendingIntent = null;
        private BroadcastReceiver mAmbientUpdateBroadcastReceiver = null;
        private AlarmManager mAmbientUpdateAlarmManager = null;
        private IntentFilter mAmbientUpdateIntentFilter = null;
        private boolean mAmbientUpdateReceiverRegistered = false;

        private Handler mAmbientUpdateHandler = null;
        private Runnable mAmbientUpdateRunnable = null;

        /**
         * Handle time updates in ambient mode.
         */
        private void handleAmbientUpdate() {
            if (!mAmbient || mAmbientUpdateRateSeconds == 0) {
                return;
            }
            if (mAmbientUpdateRateSeconds <= 5) {
                if (mAmbientUpdateHandler == null) {
                    mAmbientUpdateHandler = new Handler();
                    mAmbientUpdateRunnable = new Runnable() {
                        @Override
                        public void run() {
                            invalidate();
                            handleAmbientUpdate();
                        }
                    };
                }
                long timeMs = System.currentTimeMillis();
                long delayMs = (mAmbientUpdateRateSeconds * 1000) - timeMs % (mAmbientUpdateRateSeconds * 1000);
                long triggerTimeMs = timeMs + delayMs;
                mAmbientUpdateHandler.postDelayed(mAmbientUpdateRunnable, delayMs);
                return;
            }

            if (mAmbientUpdateAlarmManager == null) {
                mAmbientUpdateAlarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                mAmbientUpdateIntent = new Intent(AMBIENT_UPDATE_ACTION);
                mAmbientUpdatePendingIntent = PendingIntent.getBroadcast(
                        getBaseContext(), 0, mAmbientUpdateIntent, PendingIntent.FLAG_UPDATE_CURRENT
                );
                mAmbientUpdateBroadcastReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        invalidate();
                        handleAmbientUpdate();
                    }
                };
                mAmbientUpdateIntentFilter = new IntentFilter(AMBIENT_UPDATE_ACTION);
            }
            if (!mAmbientUpdateReceiverRegistered) {
                PilotWatchFace.this.registerReceiver(mAmbientUpdateBroadcastReceiver, mAmbientUpdateIntentFilter);
                mAmbientUpdateReceiverRegistered = true;
            }

            long timeMs = System.currentTimeMillis();
            long delayMs = (mAmbientUpdateRateSeconds * 1000) - timeMs % (mAmbientUpdateRateSeconds * 1000);
            long triggerTimeMs = timeMs + delayMs;
            mAmbientUpdateAlarmManager.setExact(RTC_WAKEUP, triggerTimeMs, mAmbientUpdatePendingIntent);
        }

        private void startAmbientUpdates() {
            if (!mAmbient || mAmbientUpdateRateSeconds == 0) {
                return;
            }
            handleAmbientUpdate();
        }

        private void stopAmbientUpdates() {
            if (!mAmbient || mAmbientUpdateRateSeconds == 0) {
                return;
            }
            if (mAmbientUpdateRateSeconds <= 5) {
                if (mAmbientUpdateHandler != null) {
                    mAmbientUpdateHandler.removeCallbacks(mAmbientUpdateRunnable);
                }
            }
            if (mAmbientUpdateAlarmManager != null) {
                mAmbientUpdateAlarmManager.cancel(mAmbientUpdatePendingIntent);
            }
            if (mAmbientUpdateReceiverRegistered) {
                PilotWatchFace.this.unregisterReceiver(mAmbientUpdateBroadcastReceiver);
                mAmbientUpdateReceiverRegistered = false;
            }
        }

        private void setCustomTimeout(int seconds) {
            if (seconds > 0) {
                mCustomTimeoutSeconds = seconds;
                acquireWakeLock();
            } else {
                mCustomTimeoutSeconds = 0;
                releaseWakeLock();
            }
        }

        private void clearCustomTimeout() {
            mCustomTimeoutSeconds = 0;
            releaseWakeLock();
        }

        /**
         * Call after user activity, screen change, etc.
         */
        private void clearIdle() {
            if (mCustomTimeoutSeconds <= 0) {
                return;
            }
            acquireWakeLock();
        }

        /**
         * Called after every draw.
         * Use this to clear a 'keep screen on' flag or something.
         * DO NOT DELETE THIS METHOD.  Keep it as a placeholder.
         */
        private void checkIdle() {
            if (mCustomTimeoutSeconds <= 0) {
                return;
            }
            // DO NOT DELETE THIS METHOD.
        }

        private void acquireWakeLock() {
            if (mFullWakeLockDenied) {
                return;
            }
            if (mPowerManager == null) {
                try {
                    mPowerManager = (PowerManager) getSystemService(POWER_SERVICE);
                } catch (Exception e) {
                    Log.e(TAG, "error creating PowerManager object: " + e.getLocalizedMessage());
                    mFullWakeLockDenied = true;
                    return;
                }
            }
            if (mWakeLock == null) {
                try {
                    mWakeLock = mPowerManager.newWakeLock(
                            PowerManager.FULL_WAKE_LOCK,
                            "PilotWatch::WakeLockTag"
                    );
                } catch (Exception e) {
                    Log.e(TAG, "error creating full wake lock: " + e.getLocalizedMessage());
                    mFullWakeLockDenied = true;
                    return;
                }
            }
            mWakeLock.acquire(mCustomTimeoutSeconds * 1000L);
        }

        private void releaseWakeLock() {
            if (mFullWakeLockDenied) {
                return;
            }
            if (mWakeLock != null) {
                mWakeLock.release();
            }
        }

        private float getClockDialTextSizePx(float vmin) {
            return mClockDialDiameterPx * vmin;
        }

        private float vminToPx(float vmin, boolean atLeastOnePixel) {
            float result = mClockDialDiameterPx * vmin;
            if (atLeastOnePixel) {
                result = Math.max(result, 1f);
            }
            return result;
        }

        private float vminToPx(float vmin) {
            return vminToPx(vmin, false);
        }
    }

    private void drawVerticallyCenteredText(Canvas canvas, String text, float x, float y, Paint paint) {
        canvas.drawText(text, x, y + paint.getTextSize() * TEXT_CAP_HEIGHT / 2, paint);
    }
}
