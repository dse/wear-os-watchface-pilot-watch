package com.webonastick.watchface.pilotwatch;

/**
 * Pilot Watch watchface for Android Wear / Wear OS.
 *
 * Notes about variable names:
 * - A lot of them end with units.
 * - Px is pixels.
 * - Vmin is the unit of a multiple of the minimum of the viewport's width or height.
 *   On a 300 x 400 display, 0.5 vmin = 150px for example.
 *   It's kind of like the vmin unit in CSS, except it's not a percentage, it's a percentage divided by 100.
 * - All angles are assumed to be DEGREES unless otherwise indicated.
 * - Pct means Percentage
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

/**
 * Analog watch face with a ticking second hand. In ambient mode, the second hand isn't
 * shown. On devices with low-bit ambient mode, the hands are drawn without anti-aliasing in ambient
 * mode. The watch face is drawn with less contrast in mute mode.
 * <p>
 * Important Note: Because watch face apps do not have a default Activity in
 * their project, you will need to set your Configurations to
 * "Do not launch Activity" for both the Wear and/or Application modules. If you
 * are unsure how to do this, please review the "Run Starter project" section
 * in the Google Watch Face Code Lab:
 * https://codelabs.developers.google.com/codelabs/watchface/index.html#0
 */
public class PilotWatchFace extends CanvasWatchFaceService {

    /*
     * Updates rate in milliseconds for interactive mode. We update once a second to advance the
     * second hand.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
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

        private float mRadiusPx;
        private float mDiameterPx;
        private float mCenterXPx;
        private float mCenterYPx;
        private int mWidthPx;
        private int mHeightPx;

        private Bitmap mBackgroundBitmap;
        private Bitmap mBackgroundBitmapZoomSubDial4;
        private Bitmap mAmbientBackgroundBitmap;

        private long mUpdateRateMs = INTERACTIVE_UPDATE_RATE_MS;

        private boolean mPutChronographSecondsOnSubDial = true;

        private boolean mDemoTimeMode = false;
        private boolean mEmulatorMode = false;

        private class WatchDial {
            public WeakReference<Engine> engineWeakReference;

            public float diameterVmin = 0.5f;
            public float centerXVmin = 0.0f;
            public float centerYVmin = 0.0f;
            public int numTicks1 = 12;
            public int numTicks2 = 60;
            public int numTicks3 = 0;
            public float ticksOuterDiameter = 1.0f;
            public float ticks1InnerDiameter = 0.80f;
            public float ticks2InnerDiameter = 0.86f;
            public float ticks3InnerDiameter = 0.92f;
            public float ticks1StrokeWidthVmin = 0.01f;
            public float ticks2StrokeWidthVmin = 0.01f;
            public float ticks3StrokeWidthVmin = 0.01f;
            public boolean nonAmbientOnly = true;
            public float startAngle = 0f;
            public float endAngle = 360f;
            public float excludeTicksFrom = 0f;
            public float excludeTicksTo = 0f;

            public float circle1Diameter = 0f;
            public float circle2Diameter = 0f;
            public float circleStrokeWidthVmin = 0f;

            public ArrayList<Pair<Float, String>> textPairs = new ArrayList<Pair<Float, String>>();
            public float textSizeVmin = 0.05f;

            public float darkOpacity = 0f;

            private float radiusPx;
            private float centerXPx;
            private float centerYPx;
            private float tickOuterPx;
            private float tickInner1Px;
            private float tickInner2Px;
            private float tickInner3Px;
            private float ticks1StrokeWidthPx;
            private float ticks2StrokeWidthPx;
            private float ticks3StrokeWidthPx;

            private float leftBoundaryPx;
            private float rightBoundaryPx;
            private float topBoundaryPx;
            private float bottomBoundaryPx;

            private int shadowColor = Color.BLACK;
            private float shadowDXPx = 0;
            private float shadowDYPx = 1;

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

            public void update() {
                Engine engine = engineWeakReference.get();
                radiusPx = diameterVmin * engine.mRadiusPx;
                centerXPx = engine.mCenterXPx + centerXVmin * engine.mDiameterPx;
                centerYPx = engine.mCenterYPx + centerYVmin * engine.mDiameterPx;
                tickOuterPx = radiusPx * ticksOuterDiameter;
                tickInner1Px = radiusPx * ticks1InnerDiameter;
                tickInner2Px = radiusPx * ticks2InnerDiameter;
                tickInner3Px = radiusPx * ticks3InnerDiameter;
                ticks1StrokeWidthPx = engine.mDiameterPx * ticks1StrokeWidthVmin;
                ticks2StrokeWidthPx = engine.mDiameterPx * ticks2StrokeWidthVmin;
                ticks3StrokeWidthPx = engine.mDiameterPx * ticks3StrokeWidthVmin;
                updateBoundaries();
            }

            private void updateBoundaries() {
                Engine engine = engineWeakReference.get();
                if (startAngle == 0f && endAngle == 360f) {
                    leftBoundaryPx = centerXPx - radiusPx * ticksOuterDiameter;
                    rightBoundaryPx = centerXPx + radiusPx * ticksOuterDiameter;
                    topBoundaryPx = centerYPx - radiusPx * ticksOuterDiameter;
                    bottomBoundaryPx = centerYPx + radiusPx * ticksOuterDiameter;
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
                float fudge = engine.mDiameterPx * 0.02f;
                leftBoundaryPx -= fudge;
                rightBoundaryPx += fudge;
                topBoundaryPx -= fudge;
                bottomBoundaryPx += fudge;

                /* don't put boundaries past the boundaries of the canvas */
                leftBoundaryPx = Math.max(leftBoundaryPx, 0);
                rightBoundaryPx = Math.min(rightBoundaryPx, engine.mWidthPx);
                topBoundaryPx = Math.max(topBoundaryPx, 0);
                bottomBoundaryPx = Math.min(bottomBoundaryPx, engine.mHeightPx);
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
                Engine engine = engineWeakReference.get();

                float centerXPx = this.centerXPx + (isShadow ? shadowDXPx : 0);
                float centerYPx = this.centerYPx + (isShadow ? shadowDYPx : 0);

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

                float extend = getCircleStrokeWidth() / 2;

                paint.setStrokeWidth(ticks1StrokeWidthPx);
                for (int i = 0; i <= numTicks1; i += 1) {
                    float rotation = 1.0f * i / numTicks1;
                    if (isExcluded(rotation)) {
                        continue;
                    }
                    float angle = startAngle + (endAngle - startAngle) * rotation;

                    canvas.save();
                    canvas.rotate(angle, centerXPx, centerYPx);
                    canvas.drawLine(
                            centerXPx, centerYPx - tickOuterPx - extend,
                            centerXPx, centerYPx - tickInner1Px + extend,
                            paint
                    );
                    canvas.restore();
                }

                paint.setStrokeWidth(ticks2StrokeWidthPx);
                for (int i = 0; i <= numTicks2; i += 1) {
                    if ((i * numTicks1) % numTicks2 == 0) {
                        continue;
                    }

                    float rotation = 1.0f * i / numTicks2;
                    if (isExcluded(rotation)) {
                        continue;
                    }
                    float angle = startAngle + (endAngle - startAngle) * rotation;

                    canvas.save();
                    canvas.rotate(angle, centerXPx, centerYPx);
                    canvas.drawLine(
                            centerXPx, centerYPx - tickOuterPx - extend,
                            centerXPx, centerYPx - tickInner2Px + extend,
                            paint
                    );
                    canvas.restore();
                }

                if (numTicks3 != 0 && !ambient) {
                    paint.setStrokeWidth(ticks3StrokeWidthPx);
                    for (int i = 0; i <= numTicks3; i += 1) {
                        if ((i * numTicks2) % numTicks3 == 0) {
                            continue;
                        }
                        if ((i * numTicks1) % numTicks3 == 0) {
                            continue;
                        }

                        float rotation = 1.0f * i / numTicks3;
                        if (isExcluded(rotation)) {
                            continue;
                        }
                        float angle = startAngle + (endAngle - startAngle) * rotation;

                        canvas.save();
                        canvas.rotate(angle, centerXPx, centerYPx);
                        canvas.drawLine(
                                centerXPx, centerYPx - tickOuterPx - extend,
                                centerXPx, centerYPx - tickInner3Px + extend,
                                paint
                        );
                        canvas.restore();
                    }
                }
            }

            public float getCircleStrokeWidth() {
                Engine engine = engineWeakReference.get();
                if (circleStrokeWidthVmin != 0f) {
                    return circleStrokeWidthVmin * engine.mDiameterPx;
                }
                if (ticks3StrokeWidthVmin != 0f) {
                    return ticks3StrokeWidthVmin * engine.mDiameterPx;
                }
                if (ticks2StrokeWidthVmin != 0f) {
                    return ticks2StrokeWidthVmin * engine.mDiameterPx;
                }
                if (ticks1StrokeWidthVmin != 0f) {
                    return ticks1StrokeWidthVmin * engine.mDiameterPx;
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
                paint.setStrokeWidth(strokeWidthPx);

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
                textPaint.setTextSize(engine.mDiameterPx * textSizeVmin);
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
                        engine.mWidthPx / (rightBoundaryPx - leftBoundaryPx),
                        engine.mHeightPx / (bottomBoundaryPx - topBoundaryPx)
                );
                canvas.scale(scale, scale, newCenterX, newCenterY);
                canvas.translate(-dx, -dy);
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
            float dx = centerX - mCenterXPx;
            float dy = centerY - mCenterYPx;
            float scaleX = mWidthPx / (x2 - x1);
            float scaleY = mHeightPx / (y2 - y1);
            float scale = Math.min(scaleX, scaleY);
            canvas.scale(scale, scale, mCenterXPx, mCenterYPx);
            canvas.translate(-dx, -dy);
        }

        private class WatchHand {
            public WeakReference<WatchDial> watchDialWeakReference;

            public Canvas canvas;
            public Paint paint;
            public Path path;
            public int color;
            public boolean nonAmbientOnly = true;
            public boolean hasArrowHead = false;
            public float arrowHeadAngle = 45f;
            public float arrowHeadSize = 3f;
            public float lengthPctRadius = 1f;
            public float widthVmin = 0.005f;
            public float shroudThingyRadius = 0.03f;
            public float shroudThingyHoleRadius = 0.01f;

            private float lengthPx;
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
                widthPx = widthVmin * engine.mDiameterPx;

                shroudThingyHoleRadiusPx = shroudThingyHoleRadius * engine.mRadiusPx;
                shroudThingyRadiusPx = shroudThingyRadius * engine.mRadiusPx;
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

                if (hasArrowHead) {
                    float arrowheadDX1 = widthPx * arrowHeadSize / 2;
                    float arrowheadY1 = dial.centerYPx - lengthPx + widthPx * arrowHeadSize / 2 / (float) Math.tan(((float) Math.PI) / 180f * arrowHeadAngle / 2);
                    path.moveTo(dial.centerXPx - widthPx / 2, dial.centerYPx);
                    path.lineTo(dial.centerXPx - widthPx / 2, arrowheadY1);
                    path.lineTo(dial.centerXPx - arrowheadDX1, arrowheadY1);
                    path.lineTo(dial.centerXPx, dial.centerYPx - lengthPx);
                    path.lineTo(dial.centerXPx + arrowheadDX1, arrowheadY1);
                    path.lineTo(dial.centerXPx + widthPx / 2, arrowheadY1);
                    path.lineTo(dial.centerXPx + widthPx / 2, dial.centerYPx);
                    path.close();
                } else {
                    path.moveTo(dial.centerXPx - widthPx / 2, dial.centerYPx);
                    path.lineTo(dial.centerXPx - widthPx / 2, dial.centerYPx - lengthPx);
                    path.lineTo(dial.centerXPx + widthPx / 2, dial.centerYPx - lengthPx);
                    path.lineTo(dial.centerXPx + widthPx / 2, dial.centerYPx);
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

        private boolean mZoomOnSubDial4 = false;

        @Override
        public void onCreate(SurfaceHolder holder) {
            if (Build.MODEL.startsWith("sdk_") || Build.FINGERPRINT.contains("/sdk_")) {
                mEmulatorMode = true;
            }

            super.onCreate(holder);

            WatchFaceStyle.Builder builder;
            WatchFaceStyle style;

            builder = new WatchFaceStyle.Builder(PilotWatchFace.this);
            builder = builder.setAcceptsTapEvents(true);
            style = builder.build();

            setWatchFaceStyle(style);

            mCalendar = Calendar.getInstance();

            setUpdateRate();

            mBackgroundColor = ContextCompat.getColor(getApplicationContext(), R.color.background_color);
            mHourHandColor = ContextCompat.getColor(getApplicationContext(), R.color.hour_hand_color);
            mMinuteHandColor = ContextCompat.getColor(getApplicationContext(), R.color.minute_hand_color);
            mSecondHandColor = ContextCompat.getColor(getApplicationContext(), R.color.second_hand_color);
            mTickColor = ContextCompat.getColor(getApplicationContext(), R.color.tick_color);

            mMainDial = new WatchDial(this);
            mMainDial.diameterVmin = 1f;
            mMainDial.centerXVmin = 0.0f;
            mMainDial.centerYVmin = 0.0f;
            mMainDial.numTicks1 = 12;
            mMainDial.numTicks2 = 60;
            mMainDial.numTicks3 = 300;
            mMainDial.ticksOuterDiameter = 0.99f;
            mMainDial.ticks1InnerDiameter = 0.90f;
            mMainDial.ticks2InnerDiameter = 0.93f;
            mMainDial.ticks3InnerDiameter = 0.96f;
            mMainDial.ticks1StrokeWidthVmin = 0.01f;
            mMainDial.ticks2StrokeWidthVmin = 0.005f;
            mMainDial.ticks3StrokeWidthVmin = 0.0025f;
            mMainDial.nonAmbientOnly = false;
            mMainDial.circle1Diameter = 0.99f;
            mMainDial.circle2Diameter = 0.96f;
            mMainDial.circleStrokeWidthVmin = 0.0025f;

            mSubDial1 = new WatchDial(this);
            mSubDial1.diameterVmin = 0.3f;
            mSubDial1.centerXVmin = 0f;
            mSubDial1.centerYVmin = -0.25f;
            mSubDial1.numTicks1 = 10;
            mSubDial1.numTicks2 = 50;
            mSubDial1.ticksOuterDiameter = 1f;
            mSubDial1.ticks1InnerDiameter = 0.80f;
            mSubDial1.ticks2InnerDiameter = 0.90f;
            mSubDial1.ticks1StrokeWidthVmin = 0.005f;
            mSubDial1.ticks2StrokeWidthVmin = 0.0025f;
            mSubDial1.nonAmbientOnly = true;
            mSubDial1.circle1Diameter = 1f;
            mSubDial1.circle2Diameter = 0.90f;
            mSubDial1.circleStrokeWidthVmin = 0.0025f;
            mSubDial1.darkOpacity = 0.2f;
            mSubDial1.addText(0.0f, "0");
            mSubDial1.addText(0.2f, "2");
            mSubDial1.addText(0.4f, "4");
            mSubDial1.addText(0.6f, "6");
            mSubDial1.addText(0.8f, "8");

            mSubDial2 = new WatchDial(this);
            mSubDial2.diameterVmin = 0.3f;
            mSubDial2.centerXVmin = -0.25f;
            mSubDial2.centerYVmin = 0f;
            mSubDial2.numTicks1 = 12;
            mSubDial2.numTicks2 = 60;
            mSubDial2.ticksOuterDiameter = 1f;
            mSubDial2.ticks1InnerDiameter = 0.80f;
            mSubDial2.ticks2InnerDiameter = 0.90f;
            mSubDial2.ticks1StrokeWidthVmin = 0.005f;
            mSubDial2.ticks2StrokeWidthVmin = 0.0025f;
            mSubDial2.nonAmbientOnly = true;
            mSubDial2.circle1Diameter = 1f;
            mSubDial2.circle2Diameter = 0.9f;
            mSubDial2.circleStrokeWidthVmin = 0.0025f;
            mSubDial2.darkOpacity = 0.2f;
            mSubDial2.addText(0.00f, "12");
            mSubDial2.addText(0.25f, "3");
            mSubDial2.addText(0.50f, "6");
            mSubDial2.addText(0.75f, "9");

            mSubDial3 = new WatchDial(this);
            mSubDial3.diameterVmin = 0.3f;
            mSubDial3.centerXVmin = 0f;
            mSubDial3.centerYVmin = 0.25f;
            mSubDial3.numTicks1 = 12;
            mSubDial3.numTicks2 = 60;
            mSubDial3.ticksOuterDiameter = 1f;
            mSubDial3.ticks1InnerDiameter = 0.80f;
            mSubDial3.ticks2InnerDiameter = 0.90f;
            mSubDial3.ticks1StrokeWidthVmin = 0.005f;
            mSubDial3.ticks2StrokeWidthVmin = 0.0025f;
            mSubDial3.nonAmbientOnly = true;
            mSubDial3.circle1Diameter = 1f;
            mSubDial3.circle2Diameter = 0.9f;
            mSubDial3.circleStrokeWidthVmin = 0.0025f;
            mSubDial3.darkOpacity = 0.2f;
            mSubDial3.addText(0.00f, "60");
            mSubDial3.addText(0.25f, "15");
            mSubDial3.addText(0.50f, "30");
            mSubDial3.addText(0.75f, "45");

            mSubDial4 = new WatchDial(this);
            mSubDial4.diameterVmin = 0.5f;
            mSubDial4.centerXVmin = 0.125f;
            mSubDial4.centerYVmin = 0f;
            mSubDial4.numTicks1 = 2;
            mSubDial4.numTicks2 = 10;
            mSubDial4.numTicks3 = 20;
            mSubDial4.ticksOuterDiameter = 1f;
            mSubDial4.ticks1InnerDiameter = 0.80f;
            mSubDial4.ticks2InnerDiameter = 0.86f;
            mSubDial4.ticks3InnerDiameter = 0.92f;
            mSubDial4.ticks1StrokeWidthVmin = 0.01f;
            mSubDial4.ticks2StrokeWidthVmin = 0.005f;
            mSubDial4.ticks3StrokeWidthVmin = 0.0025f;
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

            mChronographSecondFractionHand = new WatchHand(mSubDial1);
            mChronographSecondFractionHand.color = mSecondHandColor;
            mChronographSecondFractionHand.nonAmbientOnly = true;
            mChronographSecondFractionHand.lengthPctRadius = 0.9f;
            mChronographSecondFractionHand.widthVmin = 0.01f;
            mChronographSecondFractionHand.shadowRadiusPx = 2f;

            mChronographSecondHand = new WatchHand(mSubDial3);
            mChronographSecondHand.color = mSecondHandColor;
            mChronographSecondHand.nonAmbientOnly = true;
            mChronographSecondHand.lengthPctRadius = 0.9f;
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
            mChronographHourHand.lengthPctRadius = 0.8f * 0.7f;
            mChronographHourHand.widthVmin = 0.01f;
            mChronographHourHand.shadowRadiusPx = 2f;

            mSecondHand = new WatchHand(mMainDial);
            mSecondHand.color = mSecondHandColor;
            mSecondHand.nonAmbientOnly = true;
            mSecondHand.lengthPctRadius = 0.95f;
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
            mHourHand.lengthPctRadius = 0.9f * 0.7f;
            mHourHand.widthVmin = 0.02f;
            mHourHand.shadowRadiusPx = 4f;

            mBatteryHand = new WatchHand(mSubDial4);
            mBatteryHand.color = mSecondHandColor;
            mBatteryHand.nonAmbientOnly = false;
            mBatteryHand.hasArrowHead = true;
            mBatteryHand.lengthPctRadius = 0.9f;
            mBatteryHand.widthVmin = 0.01f;
            mBatteryHand.shadowRadiusPx = 2f;

            clearIdle();
            updateDials();
            updateHands();

            setCustomTimeout(15);
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
                    mUpdateRateMs = 200;
                }
            } else {
                if (stopwatchRunning) {
                    mUpdateRateMs = 50;
                } else {
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

            mDiameterPx = Math.min(width, height);
            mRadiusPx = mDiameterPx / 2f;
            mCenterXPx = width / 2f;
            mCenterYPx = height / 2f;
            mWidthPx = width;
            mHeightPx = height;

            mDayDateTextPaint = new Paint();
            mDayDateTextPaint.setAntiAlias(true);
            mDayDateTextPaint.setTextSize(mDiameterPx * DAY_DATE_TEXT_SIZE);
            mDayDateTextPaint.setColor(Color.BLACK);
            mDayDateTextPaint.setTypeface(mTypeface);
            mDayDateTextPaint.setTextAlign(Paint.Align.CENTER);

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
            mBackgroundBitmap = Bitmap.createBitmap(mWidthPx, mHeightPx, Bitmap.Config.ARGB_8888);
            Canvas backgroundCanvas = new Canvas(mBackgroundBitmap);
            drawClockDial(backgroundCanvas, false);
            mMainDial.draw(backgroundCanvas, false);
            mSubDial1.draw(backgroundCanvas, false);
            mSubDial2.draw(backgroundCanvas, false);
            mSubDial3.draw(backgroundCanvas, false);
            mSubDial4.draw(backgroundCanvas, false);
        }

        private void initBackgroundBitmapZoomSubDial4() {
            mBackgroundBitmapZoomSubDial4 = Bitmap.createBitmap(mWidthPx, mHeightPx, Bitmap.Config.ARGB_8888);
            Canvas backgroundCanvas = new Canvas(mBackgroundBitmapZoomSubDial4);
            zoomCanvas(backgroundCanvas, mDayDateLeft, mDayDateRight, mDayDateTop, mDayDateBottom);
            drawClockDial(backgroundCanvas, false);
            mMainDial.draw(backgroundCanvas, false);
            mSubDial1.draw(backgroundCanvas, false);
            mSubDial2.draw(backgroundCanvas, false);
            mSubDial3.draw(backgroundCanvas, false);
            mSubDial4.draw(backgroundCanvas, false);
        }

        private void initAmbientBackgroundBitmap() {
            mAmbientBackgroundBitmap = Bitmap.createBitmap(mWidthPx, mHeightPx, Bitmap.Config.ARGB_8888);
            Canvas backgroundCanvas = new Canvas(mAmbientBackgroundBitmap);
            drawClockDial(backgroundCanvas, true);
            mMainDial.draw(backgroundCanvas, true);
            mSubDial1.draw(backgroundCanvas, true);
            mSubDial2.draw(backgroundCanvas, true);
            mSubDial3.draw(backgroundCanvas, true);
            mSubDial4.draw(backgroundCanvas, true);
        }

        private final float DAY_DATE_TEXT_SIZE = 0.0625f;
        private final float DAY_DATE_OUTER = 0.87f;

        private float mDayDateTextSize;
        private float mDayWindowCenterX;
        private float mDateWindowCenterX;
        private float mDayDateTop;
        private float mDayDateBottom;
        private float mDayDateLeft;
        private float mDayDateRight;
        private Paint mDayDateTextPaint;

        private void drawClockDial(Canvas canvas, boolean ambient) {
            canvas.drawColor(Color.WHITE);

            int width = canvas.getWidth();
            int height = canvas.getHeight();

            Rect bounds = new Rect();

            float maxDayWidth = 0;
            Map<String, Integer> dayMap = mCalendar.getDisplayNames(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.getDefault());
            for (int day = 1; day <= 7; day += 1) {
                for (String dayText : dayMap.keySet()) {
                    dayText = dayText.toUpperCase();
                    mDayDateTextPaint.getTextBounds(dayText, 0, dayText.length(), bounds);
                    maxDayWidth = Math.max(maxDayWidth, bounds.width());
                }
            }

            float maxDateWidth = 0;
            for (int date = 1; date <= 31; date += 1) {
                String dateText = Integer.toString(date);
                mDayDateTextPaint.getTextBounds(dateText, 0, dateText.length(), bounds);
                maxDateWidth = Math.max(maxDateWidth, bounds.width());
            }

            mDayDateTextSize = mDiameterPx * DAY_DATE_TEXT_SIZE;

            /* 1 to 31, outer */
            float dateWindowRightX = mCenterXPx + mRadiusPx * DAY_DATE_OUTER;
            float dateWindowLeftX = dateWindowRightX - maxDateWidth - mDiameterPx * 0.02f;

            /* SUN to SAY, inner */
            float dayWindowRightX = dateWindowLeftX - mDiameterPx * 0.01f;
            float dayWindowLeftX = dayWindowRightX - maxDayWidth - mDiameterPx * 0.02f;

            mDayWindowCenterX = (dayWindowLeftX + dayWindowRightX) / 2f;
            mDateWindowCenterX = (dateWindowLeftX + dateWindowRightX) / 2f;

            mDayDateTop = mCenterYPx - mDayDateTextSize * 0.6f;
            mDayDateBottom = mCenterYPx + mDayDateTextSize * 0.6f;
            mDayDateLeft = dayWindowLeftX;
            mDayDateRight = dateWindowRightX;

            Path dialPath = new Path();
            dialPath.addRect(0, 0, width, height, Path.Direction.CW);

            Path dateWindowPath = new Path();
            dateWindowPath.addRect(
                    dateWindowLeftX, mDayDateTop,
                    dateWindowRightX, mDayDateBottom, Path.Direction.CW
            );

            Path dayWindowPath = new Path();
            dayWindowPath.addRect(
                    dayWindowLeftX, mDayDateTop,
                    dayWindowRightX, mDayDateBottom, Path.Direction.CW
            );

            dialPath.op(dateWindowPath, Path.Op.DIFFERENCE);
            dialPath.op(dayWindowPath, Path.Op.DIFFERENCE);

            Paint backgroundPaint = new Paint();
            backgroundPaint.setAntiAlias(true);
            if (ambient) {
                backgroundPaint.setColor(Color.BLACK);
            } else {
                backgroundPaint.setColor(mBackgroundColor);
                backgroundPaint.setShadowLayer(6, 0, 3, Color.BLACK);
            }
            backgroundPaint.setStyle(Paint.Style.FILL);
            canvas.drawPath(dialPath, backgroundPaint);
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
                zoomCanvas(canvas, mDayDateLeft, mDayDateRight, mDayDateTop, mDayDateBottom);
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
            mDayDateTextPaint.getTextBounds(dayText, 0, dayText.length(), dayBounds);
            Rect dateBounds = new Rect();
            mDayDateTextPaint.getTextBounds(dateText, 0, dateText.length(), dateBounds);

            canvas.drawText(dayText, mDayWindowCenterX, mCenterYPx + dayBounds.height() / 2, mDayDateTextPaint);
            canvas.drawText(dateText, mDateWindowCenterX, mCenterYPx + dateBounds.height() / 2, mDayDateTextPaint);
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

        private boolean stopwatchRunning = false;
        private long stopwatchStartTimeMs = 0;
        private long stopwatchTimeMs = 0;

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

        private int mCustomTimeoutSeconds = 0;

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

        /**
         * For keeping the watch face on longer than the standard
         * period of time.
         */

        private PowerManager mPowerManager = null;
        private PowerManager.WakeLock mWakeLock = null;
        private boolean mFullWakeLockDenied = false;

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
