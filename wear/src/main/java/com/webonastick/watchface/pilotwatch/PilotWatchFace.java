package com.webonastick.watchface.pilotwatch;

/**
 * Pilot Watch 3000 watchface for Wear OS and Android Wear.
 * <p>
 * Notes about variable names:
 * - A lot of them end with units.
 * - Px is pixels.
 * - Vmin is the unit of a multiple of the viewport's width or height,
 * whichever is less.  Example: on a 300*400 display, 0.5 vmin = 150px.
 * It's kind of like the vmin unit in CSS, except it's not a percentage,
 * it's between 0.0 and 1.0 for 0 to 100%.
 * - All angles are assumed to be DEGREES unless otherwise indicated.
 * - Pct means Percentage.
 */

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.support.v4.content.ContextCompat;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.util.Pair;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

public class PilotWatchFace extends CanvasWatchFaceService {

    /*
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
    }

    private class Engine extends CanvasWatchFaceService.Engine {
        private static final String TAG = "PilotWatchFace";

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

        private int mBackgroundColor;
        private int mHourHandColor;
        private int mMinuteHandColor;
        private int mSecondHandColor;
        private int mTickColor;

        // obsolete
//        private float mRadiusPx;
//        private float mDiameterPx;

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

        private Bitmap mBackgroundBitmap;
        private Bitmap mBackgroundBitmapZoomSubDial4;
        private Bitmap mAmbientBackgroundBitmap;

        private long mUpdateRateMs = INTERACTIVE_UPDATE_RATE_MS;

        private boolean mPutChronographSecondsOnSubDial = true;

        private boolean mDemoTimeMode = false;
        private boolean mEmulatorMode = false;

        private static final float MINIMUM_STROKE_WIDTH_PX = 1f;
        private static final float DEFAULT_TEXT_SIZE_VMIN = 0.05f;

        private WatchDial mMainDial;
        private WatchDial mSubDial1;
        private WatchDial mSubDial2;
        private WatchDial mSubDial3;
        private WatchDial mSubDial4;

        private WatchHand mHourHand;
        private WatchHand mMinuteHand;
        private WatchHand mSecondHand;

        private WatchHand mChronographSecondFractionHand;
        private WatchHand mChronographSecondHand;
        private WatchHand mChronographMinuteHand;
        private WatchHand mChronographHourHand;
        private WatchHand mBatteryHand;

        private Typeface mTypeface = Typeface.SANS_SERIF;
        private Typeface mCondensedTypeface;

        private boolean mZoomOnSubDial4 = false;

        private float mWatchFaceNameTextSizeVmin = 0.04f;
        private float mWatchFaceNameLeftOffsetVmin = 0.26f;
        private float mWatchFaceNameTopOffsetVmin = 0.26f;

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

        private boolean stopwatchRunning = false;
        private long stopwatchStartTimeMs = 0;
        private long stopwatchTimeMs = 0;

        /**
         * For keeping the watch face on longer than the standard
         * period of time.
         */
        private int mCustomTimeoutSeconds = 0;
        private PowerManager mPowerManager = null;
        private PowerManager.WakeLock mWakeLock = null;
        private boolean mFullWakeLockDenied = false;

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

            /* { 0.25f, "3" }, { 0.5f, "6" }, { 0.5f, "9" }, ... */
            public ArrayList<Pair<Float, String>> textPairs = new ArrayList<Pair<Float, String>>();

            public float textSizeVmin = DEFAULT_TEXT_SIZE_VMIN;

            public float darkOpacity = 0f;

            private float radiusPx;
            private float centerXPx;
            private float centerYPx;

            private float leftBoundaryPx;
            private float rightBoundaryPx;
            private float topBoundaryPx;
            private float bottomBoundaryPx;

            private int shadowColor = Color.BLACK;
            private float shadowDXPx = 0;
            private float shadowDYPx = 1;

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
                centerXPx = engine.mSurfaceCenterXPx + centerXVmin * engine.mClockDialDiameterPx;
                centerYPx = engine.mSurfaceCenterYPx + centerYVmin * engine.mClockDialDiameterPx;
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
                        angle = (float) Math.floor(angle / 90f) * 90f;  // e.g., 126 => 90
                        angle = Math.min(angle, maxAngle);
                    }
                }

                /* add fudge factor */
                float fudge = engine.mClockDialDiameterPx * 0.02f;
                leftBoundaryPx -= fudge;
                rightBoundaryPx += fudge;
                topBoundaryPx -= fudge;
                bottomBoundaryPx += fudge;

                /* don't put boundaries past the boundaries of the canvas */
                leftBoundaryPx = Math.max(leftBoundaryPx, 0);
                rightBoundaryPx = Math.min(rightBoundaryPx, engine.mSurfaceWidthPx);
                topBoundaryPx = Math.max(topBoundaryPx, 0);
                bottomBoundaryPx = Math.min(bottomBoundaryPx, engine.mSurfaceHeightPx);
            }

            public void draw(Canvas canvas, Boolean ambient) {
                Engine engine = engineWeakReference.get();

                if (ambient && nonAmbientOnly) {
                    return;
                }

                drawOpaqueLayer(canvas, ambient);
                if (!ambient && shadowColor != 0 && (shadowDXPx != 0 || shadowDYPx != 0)) {
                    drawTicks(canvas, ambient, true);
                    drawCircles(canvas, ambient, true);
                    drawText(canvas, ambient, true);
                }
                drawTicks(canvas, ambient);
                drawCircles(canvas, ambient);
                drawText(canvas, ambient);
            }

            public void drawOpaqueLayer(Canvas canvas, Boolean ambient) {
                if (ambient) {
                    return;
                }
                Engine engine = engineWeakReference.get();

                Paint paint = new Paint();
                paint.setAntiAlias(true);
                paint.setColor(Color.BLACK);
                paint.setAlpha(Math.round(255f * Math.min(1f, Math.max(darkOpacity, 0f)) + 0.5f));
                paint.setStyle(Paint.Style.FILL);

                canvas.drawCircle(centerXPx, centerYPx, radiusPx, paint);
            }

            public void drawTicks(Canvas canvas, Boolean ambient) {
                drawTicks(canvas, ambient, false);
            }

            public void drawTicks(Canvas canvas, Boolean ambient, Boolean isShadow) {
                if (isShadow && (ambient || (shadowDXPx == 0 && shadowDYPx == 0))) {
                    return;
                }

                for (WatchDialTickSet tickSet : tickSets) {
                    tickSet.draw(canvas, ambient, isShadow);
                }
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

            public void drawCircles(Canvas canvas, Boolean ambient) {
                drawCircles(canvas, ambient, false);
            }

            public void drawCircles(Canvas canvas, Boolean ambient, Boolean isShadow) {
                if (isShadow && (ambient || (shadowDXPx == 0 && shadowDYPx == 0))) {
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

            public void drawText(Canvas canvas, Boolean ambient) {
                drawText(canvas, ambient, false);
            }

            public void drawText(Canvas canvas, Boolean ambient, Boolean isShadow) {
                if (isShadow && (ambient || (shadowDXPx == 0 && shadowDYPx == 0))) {
                    return;
                }

                Engine engine = engineWeakReference.get();

                float centerXPx = this.centerXPx + (isShadow ? shadowDXPx : 0);
                float centerYPx = this.centerYPx + (isShadow ? shadowDYPx : 0);

                Paint textPaint = new Paint();
                textPaint.setTypeface(mTypeface);
                if (isShadow) {
                    textPaint.setColor(Color.BLACK);
                } else {
                    textPaint.setColor(Color.WHITE);
                }
                textPaint.setTextSize(engine.mSurfaceVminPx * textSizeVmin);
                textPaint.setAntiAlias(true);
                textPaint.setTextAlign(Paint.Align.CENTER);
                textPaint.setStyle(Paint.Style.FILL);

                if (textPairs != null) {
                    for (Pair<Float, String> textPair : textPairs) {
                        float rotation = textPair.first;
                        float angle = getCanvasRotationAngle(rotation);
                        String text = textPair.second;

                        canvas.save();
                        canvas.rotate(angle, centerXPx, centerYPx);
                        canvas.rotate(-angle, centerXPx, centerYPx - radiusPx * 0.6f);
                        drawVerticallyCenteredText(canvas, text, centerXPx, centerYPx - radiusPx * 0.6f, textPaint);
                        canvas.restore();
                    }
                }
            }

            public void drawArc(Canvas canvas, float diameterVmin, Paint paint, Boolean isShadow) {
                float startAngle = this.startAngle;
                float endAngle = this.endAngle;
                if (startAngle > endAngle) {
                    startAngle = this.endAngle;
                    endAngle = this.startAngle;
                }

                float centerXPx = this.centerXPx + (isShadow ? shadowDXPx : 0);
                float centerYPx = this.centerYPx + (isShadow ? shadowDYPx : 0);

                if (excludeTicksFrom == 0f && excludeTicksTo == 0f) {
                    canvas.drawArc(
                            centerXPx - diameterVmin * radiusPx, centerYPx - diameterVmin * radiusPx,
                            centerXPx + diameterVmin * radiusPx, centerYPx + diameterVmin * radiusPx,
                            startAngle - 90f,
                            endAngle - startAngle,
                            false, paint
                    );
                } else {
                    canvas.drawArc(
                            centerXPx - diameterVmin * radiusPx, centerYPx - diameterVmin * radiusPx,
                            centerXPx + diameterVmin * radiusPx, centerYPx + diameterVmin * radiusPx,
                            startAngle - 90f,
                            (endAngle - startAngle) * excludeTicksFrom,
                            false, paint
                    );
                    canvas.drawArc(
                            centerXPx - diameterVmin * radiusPx, centerYPx - diameterVmin * radiusPx,
                            centerXPx + diameterVmin * radiusPx, centerYPx + diameterVmin * radiusPx,
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

            public float getCanvasRotationAngle(float rotation) {
                return startAngle + (endAngle - startAngle) * rotation;
            }

            public float getArcRotationAngle(float rotation) {
                return startAngle - 90f + (endAngle - startAngle) * rotation;
            }

            public float getArcSweepAngle(float startRotation, float endRotation) {
                return (endAngle - startAngle) * (endRotation - startRotation);
            }

            public void zoom(Canvas canvas) {
                Engine engine = engineWeakReference.get();

                float newCenterX = (leftBoundaryPx + rightBoundaryPx) / 2f;
                float newCenterY = (topBoundaryPx + bottomBoundaryPx) / 2f;
                float dx = newCenterX - centerXPx;
                float dy = newCenterY - centerYPx;
                float scale = Math.min(
                        engine.mSurfaceWidthPx / (rightBoundaryPx - leftBoundaryPx),
                        engine.mSurfaceHeightPx / (bottomBoundaryPx - topBoundaryPx)
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
            public float strokeWidthVmin = 0.01f;
            public boolean nonAmbientOnly = false;
            public ArrayList<Integer> excludeNumberOfTicks = new ArrayList<Integer>();

            public void excludeTicks(WatchDialTickSet ts) {
                excludeNumberOfTicks.add(ts.numberOfTicks);
            }

            public void draw(Canvas canvas, Boolean ambient) {
                draw(canvas, ambient, false);
            }

            public void draw(Canvas canvas, Boolean ambient, Boolean isShadow) {
                WatchDial watchDial = watchDialWeakReference.get();
                Engine engine = watchDial.engineWeakReference.get();

                if (ambient && nonAmbientOnly) {
                    return;
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

                float extend = watchDial.getCircleStrokeWidth() / 2;
                paint.setStrokeWidth(Math.max(MINIMUM_STROKE_WIDTH_PX, strokeWidthVmin * engine.mSurfaceVminPx));

                float y1 = centerYPx - outerDiameter * watchDial.radiusPx;
                float y2 = centerYPx - innerDiameter * watchDial.radiusPx;

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
                    if (!(watchDial.startAngle == 0f || watchDial.endAngle == 0f)) {
                        if (rotation == 0f || rotation == 1f || watchDial.isExcluded(rotation)) {
                            canvas.drawLine(centerXPx, y1 - extend, centerXPx, y2 + extend, paint);
                        } else {
                            canvas.drawLine(centerXPx, y1, centerXPx, y2, paint);
                        }
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

                lengthPx = lengthPctRadius * dial.radiusPx;
                lengthBehindPx = lengthBehindPctRadius * dial.radiusPx;
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
            if (Build.MODEL.startsWith("sdk_") || Build.FINGERPRINT.contains("/sdk_")) {
                mEmulatorMode = true;
            }

            mCondensedTypeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL);

            super.onCreate(holder);

            WatchFaceStyle.Builder builder;
            WatchFaceStyle style;

            builder = new WatchFaceStyle.Builder(PilotWatchFace.this);
            builder = builder.setAcceptsTapEvents(true);
            style = builder.build();

            setWatchFaceStyle(style);

            mCalendar = Calendar.getInstance();

            setUpdateRate();

            initColors();
            initDials();
            initHands();

            clearIdle();
            updateDials();
            updateHands();

            setCustomTimeout(15);
        }

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
        private void initSubDial1() {
            mSubDial1 = new WatchDial(this);
            mSubDial1.diameterVmin = 0.35f;
            mSubDial1.centerXVmin = 0f;
            mSubDial1.centerYVmin = -0.26f;
            mSubDial1.nonAmbientOnly = false;
            mSubDial1.circle1Diameter = 1f;
            mSubDial1.circle2Diameter = 0.90f;
            mSubDial1.circleStrokeWidthVmin = 0.0025f;
            mSubDial1.darkOpacity = 0.2f;
            mSubDial1.addText(0.0f, "0");
            mSubDial1.addText(0.2f, "2");
            mSubDial1.addText(0.4f, "4");
            mSubDial1.addText(0.6f, "6");
            mSubDial1.addText(0.8f, "8");

            WatchDialTickSet tickSet1 = new WatchDialTickSet(mSubDial1);
            tickSet1.numberOfTicks = 10;
            tickSet1.outerDiameter = 1f;
            tickSet1.innerDiameter = 0.80f;
            tickSet1.strokeWidthVmin = 0.005f;
            tickSet1.nonAmbientOnly = false;
            WatchDialTickSet tickSet2 = new WatchDialTickSet(mSubDial1);
            tickSet2.numberOfTicks = 50;
            tickSet2.outerDiameter = 1f;
            tickSet2.innerDiameter = 0.90f;
            tickSet2.strokeWidthVmin = 0.0025f;
            tickSet2.nonAmbientOnly = false;
            tickSet2.excludeTicks(tickSet1);

            mSubDial1.addTickSet(tickSet1);
            mSubDial1.addTickSet(tickSet2);
        }

        /* chronograph minutes and hours */
        private void initSubDial2() {
            mSubDial2 = new WatchDial(this);
            mSubDial2.diameterVmin = 0.35f;
            mSubDial2.centerXVmin = -0.26f;
            mSubDial2.centerYVmin = 0f;
            mSubDial2.nonAmbientOnly = false;
            mSubDial2.circle1Diameter = 1f;
            mSubDial2.circle2Diameter = 0.9f;
            mSubDial2.circleStrokeWidthVmin = 0.0025f;
            mSubDial2.darkOpacity = 0.2f;
            mSubDial2.addText(0.00f, "12");
            mSubDial2.addText(0.25f, "3");
            mSubDial2.addText(0.50f, "6");
            mSubDial2.addText(0.75f, "9");

            WatchDialTickSet tickSet1 = new WatchDialTickSet(mSubDial2);
            tickSet1.numberOfTicks = 12;
            tickSet1.outerDiameter = 1f;
            tickSet1.innerDiameter = 0.80f;
            tickSet1.strokeWidthVmin = 0.005f;
            tickSet1.nonAmbientOnly = false;
            WatchDialTickSet tickSet2 = new WatchDialTickSet(mSubDial2);
            tickSet2.numberOfTicks = 60;
            tickSet2.outerDiameter = 1f;
            tickSet2.innerDiameter = 0.90f;
            tickSet2.strokeWidthVmin = 0.0025f;
            tickSet2.nonAmbientOnly = false;
            tickSet2.excludeTicks(tickSet1);

            mSubDial2.addTickSet(tickSet1);
            mSubDial2.addTickSet(tickSet2);
        }

        /* chronograph seconds */
        private void initSubDial3() {
            mSubDial3 = new WatchDial(this);
            mSubDial3.diameterVmin = 0.35f;
            mSubDial3.centerXVmin = 0f;
            mSubDial3.centerYVmin = 0.26f;
            mSubDial3.nonAmbientOnly = false;
            mSubDial3.circle1Diameter = 1f;
            mSubDial3.circle2Diameter = 0.9f;
            mSubDial3.circleStrokeWidthVmin = 0.0025f;
            mSubDial3.darkOpacity = 0.2f;
            mSubDial3.addText(0.00f, "60");
            mSubDial3.addText(0.25f, "15");
            mSubDial3.addText(0.50f, "30");
            mSubDial3.addText(0.75f, "45");

            WatchDialTickSet tickSet1 = new WatchDialTickSet(mSubDial3);
            tickSet1.numberOfTicks = 12;
            tickSet1.outerDiameter = 1f;
            tickSet1.innerDiameter = 0.80f;
            tickSet1.strokeWidthVmin = 0.005f;
            tickSet1.nonAmbientOnly = false;
            WatchDialTickSet tickSet2 = new WatchDialTickSet(mSubDial3);
            tickSet2.numberOfTicks = 60;
            tickSet2.outerDiameter = 1f;
            tickSet2.innerDiameter = 0.90f;
            tickSet2.strokeWidthVmin = 0.0025f;
            tickSet2.nonAmbientOnly = false;
            tickSet2.excludeTicks(tickSet1);

            mSubDial3.addTickSet(tickSet1);
            mSubDial3.addTickSet(tickSet2);
        }

        /* battery percentage */
        private void initSubDial4() {
            mSubDial4 = new WatchDial(this);
            mSubDial4.diameterVmin = 0.6f;
            mSubDial4.centerXVmin = 0.125f;
            mSubDial4.centerYVmin = 0f;
            mSubDial4.nonAmbientOnly = false;
            mSubDial4.startAngle = 150f;
            mSubDial4.endAngle = 30f;
            mSubDial4.excludeTicksFrom = 0.4f;
            mSubDial4.excludeTicksTo = 0.6f;
            mSubDial4.circle1Diameter = 1f;
            mSubDial4.circle2Diameter = 0.92f;
            mSubDial4.circleStrokeWidthVmin = 0.0025f;
            mSubDial4.addText(0.00f, "0%");
            mSubDial4.addText(1.00f, "100%");
            mSubDial4.textSizeVmin = 0.8f * DEFAULT_TEXT_SIZE_VMIN;

            WatchDialTickSet tickSet1 = new WatchDialTickSet(mSubDial4);
            tickSet1.numberOfTicks = 2;
            tickSet1.outerDiameter = 1f;
            tickSet1.innerDiameter = 0.80f;
            tickSet1.strokeWidthVmin = 0.01f;
            tickSet1.nonAmbientOnly = false;
            WatchDialTickSet tickSet2 = new WatchDialTickSet(mSubDial4);
            tickSet2.numberOfTicks = 10;
            tickSet2.outerDiameter = 1f;
            tickSet2.innerDiameter = 0.86f;
            tickSet2.strokeWidthVmin = 0.005f;
            tickSet2.nonAmbientOnly = false;
            tickSet2.excludeTicks(tickSet1);
            WatchDialTickSet tickSet3 = new WatchDialTickSet(mSubDial4);
            tickSet3.numberOfTicks = 20;
            tickSet3.outerDiameter = 1f;
            tickSet3.innerDiameter = 0.92f;
            tickSet3.strokeWidthVmin = 0.0025f;
            tickSet3.nonAmbientOnly = false;
            tickSet3.excludeTicks(tickSet1);
            tickSet3.excludeTicks(tickSet2);

            mSubDial4.addTickSet(tickSet1);
            mSubDial4.addTickSet(tickSet2);
            mSubDial4.addTickSet(tickSet3);
        }

        private void initDials() {
            initMainDial();
            initSubDial1();
            initSubDial2();
            initSubDial3();
            initSubDial4();
        }

        private void initHands() {
            mChronographSecondFractionHand = new WatchHand(mSubDial1);
            mChronographSecondFractionHand.color = mSecondHandColor;
            mChronographSecondFractionHand.nonAmbientOnly = true;
            mChronographSecondFractionHand.lengthPctRadius = 0.9f;
            mChronographSecondFractionHand.lengthBehindPctRadius = 0.225f;
            mChronographSecondFractionHand.widthVmin = 0.01f;
            mChronographSecondFractionHand.shadowRadiusPx = 2f;

            mChronographSecondHand = new WatchHand(mSubDial3);
            mChronographSecondHand.color = mSecondHandColor;
            mChronographSecondHand.nonAmbientOnly = true;
            mChronographSecondHand.lengthPctRadius = 0.9f;
            mChronographSecondHand.lengthBehindPctRadius = 0.225f;
            mChronographSecondHand.widthVmin = 0.01f;
            mChronographSecondHand.shadowRadiusPx = 2f;

            mChronographMinuteHand = new WatchHand(mSubDial2);
            mChronographMinuteHand.color = mMinuteHandColor;
            mChronographMinuteHand.nonAmbientOnly = true;
            mChronographMinuteHand.hasArrowHead = true;
            mChronographMinuteHand.lengthPctRadius = 0.8f;
            mChronographMinuteHand.widthVmin = 0.01f;
            mChronographMinuteHand.shadowRadiusPx = 3f;

            mChronographHourHand = new WatchHand(mSubDial2);
            mChronographHourHand.color = mHourHandColor;
            mChronographHourHand.nonAmbientOnly = true;
            mChronographHourHand.hasArrowHead = true;
            mChronographHourHand.lengthPctRadius = 0.8f * 0.6f;
            mChronographHourHand.widthVmin = 0.01f;
            mChronographHourHand.shadowRadiusPx = 2f;

            mSecondHand = new WatchHand(mMainDial);
            mSecondHand.color = mSecondHandColor;
            mSecondHand.nonAmbientOnly = true;
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

            mBatteryHand = new WatchHand(mSubDial4);
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
            mSubDial1.update();
            mSubDial2.update();
            mSubDial3.update();
            mSubDial4.update();
        }

        private void updateHands() {
            mHourHand.update();
            mMinuteHand.update();
            mSecondHand.update();
            mBatteryHand.update();
            mChronographHourHand.update();
            mChronographMinuteHand.update();
            mChronographSecondHand.update();
            mChronographSecondFractionHand.update();
        }

        private void setUpdateRate() {
            if (mPutChronographSecondsOnSubDial) {
                if (stopwatchRunning) {
                    mUpdateRateMs = 50;
                } else {
                    // seconds for time of day is on main dial.
                    mUpdateRateMs = 200;
                }
            } else {
                if (stopwatchRunning) {
                    mUpdateRateMs = 50;
                } else {
                    // seconds for time of day is on subdial; that
                    // updates once per second.
                    mUpdateRateMs = 1000;
                }
            }
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
            mBurnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            mAmbient = inAmbientMode;

            if (mAmbient) {
                mZoomOnSubDial4 = false;
            }

            updateDials();
            updateHands();

            /* Check and trigger whether or not timer should be running (only in active mode). */
            updateTimer();

            if (!mAmbient) {
                clearIdle();
            }
        }

        @Override
        public void onInterruptionFilterChanged(int interruptionFilter) {
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
            super.onSurfaceChanged(holder, format, width, height);

            mZoomOnSubDial4 = false;

//            mDiameterPx = Math.min(width, height);
//            mRadiusPx = mDiameterPx / 2f;

            mSurfaceCenterXPx = width / 2f;
            mSurfaceCenterYPx = height / 2f;
            mSurfaceWidthPx = width;
            mSurfaceHeightPx = height;
            mSurfaceVminPx = Math.min(width, height);

            mDialDiameterPx = mSurfaceVminPx - MINIMUM_STROKE_WIDTH_PX;
            mDialRadiusPx = mDialDiameterPx / 2;

            mClockDialDiameterPx = mDialDiameterPx * 1.00f;
            mClockDialRadiusPx = mClockDialDiameterPx / 2;

            mDayTextPaint = new Paint();
            mDayTextPaint.setAntiAlias(true);
            mDayTextPaint.setTextSize(mSurfaceVminPx * mDayDateTextSizeVmin);
            mDayTextPaint.setColor(Color.BLACK);
            mDayTextPaint.setTypeface(mCondensedTypeface);
            mDayTextPaint.setTextAlign(Paint.Align.CENTER);

            mDateTextPaint = new Paint();
            mDateTextPaint.setAntiAlias(true);
            mDateTextPaint.setTextSize(mSurfaceVminPx * mDayDateTextSizeVmin);
            mDateTextPaint.setColor(Color.BLACK);
            mDateTextPaint.setTypeface(mTypeface);
            mDateTextPaint.setTextAlign(Paint.Align.CENTER);

            updateDials();
            updateHands();

            initBackgroundBitmap();
            initBackgroundBitmapZoomSubDial4();
            initAmbientBackgroundBitmap();

            if (!mAmbient) {
                clearIdle();
            }
        }

        private void initBackgroundBitmap() {
            mBackgroundBitmap = Bitmap.createBitmap(mSurfaceWidthPx, mSurfaceHeightPx, Bitmap.Config.ARGB_8888);
            Canvas backgroundCanvas = new Canvas(mBackgroundBitmap);
            drawClockDial(backgroundCanvas, false);
            mMainDial.draw(backgroundCanvas, false);
            mSubDial1.draw(backgroundCanvas, false);
            mSubDial2.draw(backgroundCanvas, false);
            mSubDial3.draw(backgroundCanvas, false);
            mSubDial4.draw(backgroundCanvas, false);
        }

        private void initBackgroundBitmapZoomSubDial4() {
            mBackgroundBitmapZoomSubDial4 = Bitmap.createBitmap(mSurfaceWidthPx, mSurfaceHeightPx, Bitmap.Config.ARGB_8888);
            Canvas backgroundCanvas = new Canvas(mBackgroundBitmapZoomSubDial4);
            zoomCanvas(backgroundCanvas, mDayDateLeftPx, mDayDateRightPx, mDayDateTopPx, mDayDateBottomPx);
            drawClockDial(backgroundCanvas, false);
            mMainDial.draw(backgroundCanvas, false);
            mSubDial1.draw(backgroundCanvas, false);
            mSubDial2.draw(backgroundCanvas, false);
            mSubDial3.draw(backgroundCanvas, false);
            mSubDial4.draw(backgroundCanvas, false);
        }

        private void initAmbientBackgroundBitmap() {
            mAmbientBackgroundBitmap = Bitmap.createBitmap(mSurfaceWidthPx, mSurfaceHeightPx, Bitmap.Config.ARGB_8888);
            Canvas backgroundCanvas = new Canvas(mAmbientBackgroundBitmap);
            drawClockDial(backgroundCanvas, true);
            mMainDial.draw(backgroundCanvas, true);
            mSubDial1.draw(backgroundCanvas, true);
            mSubDial2.draw(backgroundCanvas, true);
            mSubDial3.draw(backgroundCanvas, true);
            mSubDial4.draw(backgroundCanvas, true);
        }

        private void drawClockDial(Canvas canvas, boolean ambient) {
            canvas.drawColor(Color.WHITE);

            int widthPx = canvas.getWidth();
            int heightPx = canvas.getHeight();

            Rect dayBounds = new Rect();
            Rect dateBounds = new Rect();

            float maxDayWidthPx = 0;
            Map<String, Integer> dayMap = mCalendar.getDisplayNames(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.getDefault());
            for (int day = 1; day <= 7; day += 1) {
                for (String dayText : dayMap.keySet()) {
                    dayText = dayText.toUpperCase();
                    mDayTextPaint.getTextBounds(dayText, 0, dayText.length(), dayBounds);
                    maxDayWidthPx = Math.max(maxDayWidthPx, dayBounds.width());
                }
            }

            float maxDateWidthPx = 0;
            for (int date = 1; date <= 31; date += 1) {
                String dateText = Integer.toString(date);
                mDateTextPaint.getTextBounds(dateText, 0, dateText.length(), dateBounds);
                maxDateWidthPx = Math.max(maxDateWidthPx, dateBounds.width());
            }

            mDayDateTextSizePx = mSurfaceVminPx * mDayDateTextSizeVmin;

            /* 1 to 31, outer */
            float dateWindowRightXPx = mSurfaceCenterXPx + mClockDialRadiusPx * mDayDateOuterVmin;
            float dateWindowLeftXPx = dateWindowRightXPx - maxDateWidthPx - mClockDialDiameterPx * 0.02f;

            /* SUN to SAT, inner */
            float dayWindowRightXPx = dateWindowLeftXPx - mClockDialDiameterPx * 0.01f;
            float dayWindowLeftXPx = dayWindowRightXPx - maxDayWidthPx - mClockDialDiameterPx * 0.02f;

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

            drawWatchFaceName(canvas, ambient, true);
            drawWatchFaceName(canvas, ambient, false);
        }

        private void drawWatchFaceName(Canvas canvas, Boolean ambient, Boolean isShadow) {
            if (isShadow && ambient) {
                return;
            }

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
            textPaint.setTextSize(mWatchFaceNameTextSizeVmin * mSurfaceVminPx);
            textPaint.setTypeface(mTypeface);
            textPaint.setTextAlign(Paint.Align.CENTER);

            float dx = isShadow ? 0f : 0f;
            float dy = isShadow ? 1f : 0f;

            float watchFaceNameXPx = mSurfaceCenterXPx - mClockDialDiameterPx * mWatchFaceNameLeftOffsetVmin;
            float watchFaceNameYPx = mSurfaceCenterYPx - mClockDialDiameterPx * mWatchFaceNameTopOffsetVmin;
            watchFaceNameYPx -= 0.5 * mSurfaceVminPx * mWatchFaceNameTextSizeVmin;

            canvas.drawText("PILOT", watchFaceNameXPx + dx, watchFaceNameYPx + dy, textPaint);
            watchFaceNameYPx += mSurfaceVminPx * mWatchFaceNameTextSizeVmin;
            canvas.drawText("WATCH", watchFaceNameXPx + dx, watchFaceNameYPx + dy, textPaint);
            watchFaceNameYPx += mSurfaceVminPx * mWatchFaceNameTextSizeVmin;
            canvas.drawText("3000", watchFaceNameXPx + dx, watchFaceNameYPx + dy, textPaint);
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
                    if (mZoomOnSubDial4) {
                        mZoomOnSubDial4 = false;
                    } else {
                        if (mSubDial1.contains(x, y)) {
                            stopwatchButton1();
                            updateTimer();
                        } else if (mSubDial2.contains(x, y)) {
                            stopwatchButton2();
                            updateTimer();
                        } else if (mSubDial3.contains(x, y) && mEmulatorMode) {
                            mDemoTimeMode = !mDemoTimeMode;
                            updateTimer();
                        } else if (mSubDial4.contains(x, y)) {
                            mZoomOnSubDial4 = true;
                            updateTimer();
                        }
                    }
                    break;
            }
            invalidate();
            if (!mAmbient) {
                clearIdle();
            }
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            long now = System.currentTimeMillis();

            if (mDemoTimeMode) {
                mCalendar.set(2019, 5 /* JUN */, 30, 10, 10, 32);
            } else {
                mCalendar.setTimeInMillis(now);
            }

            drawBackground(canvas);
            if (mZoomOnSubDial4) {
                canvas.save();
                zoomCanvas(canvas, mDayDateLeftPx, mDayDateRightPx, mDayDateTopPx, mDayDateBottomPx);
            }
            drawDate(canvas);
            if (!mAmbient) {
                drawStopwatch(canvas);
            }
            drawBattery(canvas);
            drawWatchFace(canvas);
            if (mZoomOnSubDial4) {
                canvas.restore();
            }

            checkIdle();
        }

        private void drawBackground(Canvas canvas) {
            if (mAmbient && (mLowBitAmbient || mBurnInProtection)) {
                canvas.drawBitmap(mAmbientBackgroundBitmap, 0, 0, null);
            } else if (mAmbient) {
                canvas.drawBitmap(mAmbientBackgroundBitmap, 0, 0, null);
            } else if (mZoomOnSubDial4) {
                canvas.drawBitmap(mBackgroundBitmapZoomSubDial4, 0, 0, null);
            } else {
                canvas.drawBitmap(mBackgroundBitmap, 0, 0, null);
            }
        }

        private void drawDate(Canvas canvas) {
            int day = mCalendar.get(Calendar.DAY_OF_WEEK);
            int date = mCalendar.get(Calendar.DAY_OF_MONTH);

            String dayText = mCalendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.getDefault());
            dayText = dayText.toUpperCase();
            String dateText = Integer.toString(date);

            Rect dayBounds = new Rect();
            mDayTextPaint.getTextBounds(dayText, 0, dayText.length(), dayBounds);
            Rect dateBounds = new Rect();
            mDateTextPaint.getTextBounds(dateText, 0, dateText.length(), dateBounds);

            float dayY = mSurfaceCenterYPx + dayBounds.height() / 2 - dayBounds.bottom;
            float dateY = mSurfaceCenterYPx + dateBounds.height() / 2 - dateBounds.bottom;

            canvas.drawText(dayText, mDayWindowCenterXPx, dayY, mDayTextPaint);
            canvas.drawText(dateText, mDateWindowCenterXPx, dateY, mDateTextPaint);
        }

        private void drawWatchFace(Canvas canvas) {
            int h;
            int m;
            int s;
            int ms;

            h = mCalendar.get(Calendar.HOUR);
            m = mCalendar.get(Calendar.MINUTE);
            s = mCalendar.get(Calendar.SECOND);
            if (mPutChronographSecondsOnSubDial) {
                ms = mCalendar.get(Calendar.MILLISECOND);
                ms = ms / 200 * 200;
            } else {
                ms = 0;
            }

            final float seconds = (float) s + (float) ms / 1000f; /* 0 to 60 */
            final float minutes = (float) m + seconds / 60f;      /* 0 to 60 */
            final float hours = (float) h + minutes / 60f;      /* 0 to 12 */

            final float secondsRotation = seconds / 60f;
            final float minutesRotation = minutes / 60f;
            final float hoursRotation = hours / 12f;

            mHourHand.draw(canvas, hoursRotation);
            mMinuteHand.draw(canvas, minutesRotation);
            mSecondHand.draw(canvas, secondsRotation);
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

        private void drawStopwatch(Canvas canvas) {
            long totalMs = getStopwatchTimeMs();

            if (mDemoTimeMode) {
                totalMs = 650 + 1000 * (32 + 60 * (10 + (60 * 10)));
            }

            final float millisecondHandRotationDegrees = (totalMs % 1000) / 1000f;
            final float secondHandRotationDegrees = (totalMs % 60000) / 60000f;
            final float minuteHandRotationDegrees = (totalMs % 3600000) / 3600000f;
            final float hourHandRotationDegrees = (totalMs % 43200000) / 43200000f;

            mChronographHourHand.draw(canvas, hourHandRotationDegrees);
            mChronographMinuteHand.draw(canvas, minuteHandRotationDegrees);
            mChronographSecondHand.draw(canvas, secondHandRotationDegrees);
            mChronographSecondFractionHand.draw(canvas, millisecondHandRotationDegrees);
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();
                /* Update time zone in case it changed while we weren't visible. */
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                unregisterReceiver();
            }

            /* Check and trigger whether or not timer should be running (only in active mode). */
            updateTimer();
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

        private void stopwatchButton1() {
            if (stopwatchRunning) {
                pauseStopwatch();
            } else {
                startStopwatch();
            }
        }

        private void stopwatchButton2() {
            if (!stopwatchRunning) {
                resetStopwatch();
            }
        }

        private void startStopwatch() {
            if (!stopwatchRunning) {
                stopwatchRunning = true;
                stopwatchStartTimeMs = System.currentTimeMillis();
            }
            setUpdateRate();
        }

        private void pauseStopwatch() {
            if (stopwatchRunning) {
                stopwatchRunning = false;
                stopwatchTimeMs += System.currentTimeMillis() - stopwatchStartTimeMs;
            }
            setUpdateRate();
        }

        private void resetStopwatch() {
            if (stopwatchRunning) {
                stopwatchRunning = false;
            }
            stopwatchStartTimeMs = 0;
            stopwatchTimeMs = 0;
            setUpdateRate();
        }

        private long getStopwatchTimeMs() {
            if (stopwatchRunning) {
                return System.currentTimeMillis() - stopwatchStartTimeMs + stopwatchTimeMs;
            }
            return stopwatchTimeMs;
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
    }

    private void drawVerticallyCenteredText(Canvas canvas, String text, float x, float y, Paint paint) {
        final Rect bounds = new Rect();
        paint.getTextBounds(text, 0, text.length(), bounds);
        canvas.drawText(text, x, y - (bounds.bottom + bounds.top) / 2f, paint);
    }
}
