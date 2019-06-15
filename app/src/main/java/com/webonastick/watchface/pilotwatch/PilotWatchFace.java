package com.webonastick.watchface.pilotwatch;

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

        private float mRadius;
        private float mDiameter;
        private float mCenterX;
        private float mCenterY;
        private int mWidth;
        private int mHeight;

        private Bitmap mBackgroundBitmap;
        private Bitmap mAmbientBackgroundBitmap;

        private long updateRateMs = INTERACTIVE_UPDATE_RATE_MS;

        private boolean putChronographSecondsOnSubdial = true;

        private boolean demoTimeMode = false;
        private boolean emulatorMode = false;

        private class WatchDial {
            public WeakReference<Engine> engineWeakReference;
            public float radius = 0.5f;
            public float centerX = 0.0f;
            public float centerY = 0.0f;
            public int ticks1 = 12;
            public int ticks2 = 60;
            public int ticks3 = 0;
            public float tickOuter = 1.0f;
            public float tickInner1 = 0.80f;
            public float tickInner2 = 0.86f;
            public float tickInner3 = 0.92f;
            public float tickWidth1 = 0.01f;
            public float tickWidth2 = 0.01f;
            public float tickWidth3 = 0.01f;
            public boolean nonAmbientOnly = true;
            public float startAngle = 0f;
            public float endAngle = 360f;
            public float excludeTicksFrom = 0f;
            public float excludeTicksTo = 0f;

            public float circle1 = 0f;
            public float circle2 = 0f;
            public float circleWidth = 0f;

            public ArrayList<Pair<Float, String>> textPairs = new ArrayList<Pair<Float, String>>();
            public float textSize = 0.05f;

            private float pRadius;
            private float pCenterX;
            private float pCenterY;
            private float pTickOuter;
            private float pTickInner1;
            private float pTickInner2;
            private float pTickInner3;
            private float pTickWidth1;
            private float pTickWidth2;
            private float pTickWidth3;

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
                pRadius = radius * engine.mRadius;
                pCenterX = engine.mCenterX + centerX * engine.mDiameter;
                pCenterY = engine.mCenterY + centerY * engine.mDiameter;
                pTickOuter = pRadius * tickOuter;
                pTickInner1 = pRadius * tickInner1;
                pTickInner2 = pRadius * tickInner2;
                pTickInner3 = pRadius * tickInner3;
                pTickWidth1 = engine.mDiameter * tickWidth1;
                pTickWidth2 = engine.mDiameter * tickWidth2;
                pTickWidth3 = engine.mDiameter * tickWidth3;
            }

            public void draw(Canvas canvas, Boolean ambient) {
                Engine engine = engineWeakReference.get();

                if (ambient && nonAmbientOnly) {
                    return;
                }

                Paint paint = new Paint();
                paint.setAntiAlias(true);
                if (ambient) {
                    paint.setColor(Color.WHITE);
                } else {
                    paint.setColor(engine.mTickColor);
                    // paint.setShadowLayer(1, 0, 1, Color.BLACK);
                }
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeCap(Paint.Cap.BUTT);

                paint.setStrokeWidth(pTickWidth1);
                for (int i = 0; i <= ticks1; i += 1) {
                    float rotation = 1.0f * i / ticks1;
                    if (isExcluded(rotation)) {
                        continue;
                    }
                    float degrees = startAngle + (endAngle - startAngle) * rotation;

                    canvas.save();
                    canvas.rotate(degrees, pCenterX, pCenterY);
                    canvas.drawLine(
                            pCenterX, pCenterY - pTickOuter,
                            pCenterX, pCenterY - pTickInner1,
                            paint
                    );
                    canvas.restore();
                }

                paint.setStrokeWidth(pTickWidth2);
                for (int i = 0; i <= ticks2; i += 1) {
                    if ((i * ticks1) % ticks2 == 0) {
                        continue;
                    }

                    float rotation = 1.0f * i / ticks2;
                    if (isExcluded(rotation)) {
                        continue;
                    }
                    float degrees = startAngle + (endAngle - startAngle) * rotation;

                    canvas.save();
                    canvas.rotate(degrees, pCenterX, pCenterY);
                    canvas.drawLine(
                            pCenterX, pCenterY - pTickOuter,
                            pCenterX, pCenterY - pTickInner2,
                            paint
                    );
                    canvas.restore();
                }

                if (ticks3 != 0 && !ambient) {
                    paint.setStrokeWidth(pTickWidth3);
                    for (int i = 0; i <= ticks3; i += 1) {
                        if ((i * ticks2) % ticks3 == 0) {
                            continue;
                        }
                        if ((i * ticks1) % ticks3 == 0) {
                            continue;
                        }

                        float rotation = 1.0f * i / ticks3;
                        if (isExcluded(rotation)) {
                            continue;
                        }
                        float degrees = startAngle + (endAngle - startAngle) * rotation;

                        canvas.save();
                        canvas.rotate(degrees, pCenterX, pCenterY);
                        canvas.drawLine(
                                pCenterX, pCenterY - pTickOuter,
                                pCenterX, pCenterY - pTickInner3,
                                paint
                        );
                        canvas.restore();
                    }
                }

                if (circleWidth != 0f) {
                    paint.setStrokeWidth(circleWidth * engine.mDiameter);
                }
                if (circle1 != 0f) {
                    drawArc(canvas, circle1, paint);
                }
                if (circle2 != 0f) {
                    drawArc(canvas, circle2, paint);
                }

                Paint textPaint = new Paint();
                textPaint.setTypeface(typeface);
                textPaint.setColor(Color.WHITE);
                textPaint.setTextSize(engine.mDiameter * textSize);
                textPaint.setAntiAlias(true);
                textPaint.setTextAlign(Paint.Align.CENTER);
                textPaint.setStyle(Paint.Style.FILL);

                if (textPairs != null) {
                    for (Pair<Float, String> textPair : textPairs) {
                        float rotation = textPair.first;
                        float angle = getCanvasRotationAngle(rotation);
                        String text = textPair.second;

                        canvas.save();
                        canvas.rotate(angle, pCenterX, pCenterY);
                        canvas.rotate(-angle, pCenterX, pCenterY - pRadius * 0.6f);
                        engine.drawVerticallyCenteredText(canvas, text, pCenterX, pCenterY - pRadius * 0.6f, textPaint);
                        canvas.restore();
                    }
                }
            }

            public void drawArc(Canvas canvas, float radius, Paint paint) {
                float startAngle = this.startAngle;
                float endAngle = this.endAngle;
                if (startAngle > endAngle) {
                    startAngle = this.endAngle;
                    endAngle = this.startAngle;
                }
                if (excludeTicksFrom == 0f && excludeTicksTo == 0f) {
                    canvas.drawArc(
                            pCenterX - radius * pRadius, pCenterY - radius * pRadius,
                            pCenterX + radius * pRadius, pCenterY + radius * pRadius,
                            startAngle - 90f,
                            endAngle - startAngle,
                            false, paint
                    );
                } else {
                    canvas.drawArc(
                            pCenterX - radius * pRadius, pCenterY - radius * pRadius,
                            pCenterX + radius * pRadius, pCenterY + radius * pRadius,
                            startAngle - 90f,
                            (endAngle - startAngle) * excludeTicksFrom,
                            false, paint
                    );
                    canvas.drawArc(
                            pCenterX - radius * pRadius, pCenterY - radius * pRadius,
                            pCenterX + radius * pRadius, pCenterY + radius * pRadius,
                            startAngle - 90f + (endAngle - startAngle) * excludeTicksTo,
                            endAngle - startAngle - (endAngle - startAngle) * excludeTicksTo,
                            false, paint
                    );
                }
            }

            public boolean contains(int x, int y) {
                float dx = 0.0f + x - pCenterX;
                float dy = 0.0f + y - pCenterY;
                return dx * dx + dy * dy <= pRadius * pRadius;
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
            public float length = 1;
            public float width = 0.01f;
            public float shroudRadius = 0.03f;
            public float shroudHoleRadius = 0.01f;

            private float pLength;
            private float pWidth;
            private float pShroudRadius;
            private float pShroudHoleRadius;

            public WatchHand(WatchDial watchDial) {
                watchDialWeakReference = new WeakReference<WatchDial>(watchDial);
            }

            public void updateDimensions() {
                WatchDial dial = watchDialWeakReference.get();
                Engine engine = dial.engineWeakReference.get();

                pLength = length * dial.pRadius;
                pWidth = width * engine.mRadius;

                pShroudHoleRadius = shroudHoleRadius * engine.mRadius;
                pShroudRadius = shroudRadius * engine.mRadius;
                if (pShroudRadius < pWidth) {
                    pShroudRadius = pWidth;
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
                    paint.setShadowLayer(0, 0, 2, Color.BLACK);
                }
                if (engine.mLowBitAmbient) {
                    paint.setAntiAlias(false);
                } else {
                    paint.setAntiAlias(true);
                }
            }

            public void updatePath() {
                WatchDial dial = watchDialWeakReference.get();
                Engine engine = dial.engineWeakReference.get();

                path = new Path();

                if (hasArrowHead) {
                    float arrowheadDX1 = pWidth * arrowHeadSize / 2;
                    float arrowheadY1 = dial.pCenterY - pLength + pWidth * arrowHeadSize / 2 / (float) Math.tan(((float) Math.PI) / 180f * arrowHeadAngle / 2);
                    path.moveTo(dial.pCenterX - pWidth / 2, dial.pCenterY);
                    path.lineTo(dial.pCenterX - pWidth / 2, arrowheadY1);
                    path.lineTo(dial.pCenterX - arrowheadDX1, arrowheadY1);
                    path.lineTo(dial.pCenterX, dial.pCenterY - pLength);
                    path.lineTo(dial.pCenterX + arrowheadDX1, arrowheadY1);
                    path.lineTo(dial.pCenterX + pWidth / 2, arrowheadY1);
                    path.lineTo(dial.pCenterX + pWidth / 2, dial.pCenterY);
                    path.close();
                } else {
                    path.moveTo(dial.pCenterX - pWidth / 2, dial.pCenterY);
                    path.lineTo(dial.pCenterX - pWidth / 2, dial.pCenterY - pLength);
                    path.lineTo(dial.pCenterX + pWidth / 2, dial.pCenterY - pLength);
                    path.lineTo(dial.pCenterX + pWidth / 2, dial.pCenterY);
                    path.close();
                }

                Path circlePath = new Path();
                circlePath.addCircle(dial.pCenterX, dial.pCenterY, pShroudRadius, Path.Direction.CW);
                path.op(circlePath, Path.Op.UNION);

                circlePath = new Path();
                circlePath.addCircle(dial.pCenterX, dial.pCenterY, pShroudHoleRadius, Path.Direction.CW);
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

                float degrees = dial.startAngle + (dial.endAngle - dial.startAngle) * rotation;

                if (engine.mAmbient && (dial.nonAmbientOnly || nonAmbientOnly)) {
                    return;
                }

                canvas.save();
                canvas.rotate(degrees, dial.pCenterX, dial.pCenterY);
                canvas.drawPath(path, paint);
                canvas.restore();
            }
        }

        WatchDial mainDial;
        WatchDial subDial1;
        WatchDial subDial2;
        WatchDial subDial3;
        WatchDial subDial4;

        WatchHand hourHand;
        WatchHand minuteHand;
        WatchHand secondHand;

        WatchHand chronographSecondFractionHand;
        WatchHand chronographSecondHand;
        WatchHand chronographMinuteHand;
        WatchHand chronographHourHand;
        WatchHand batteryHand;

        Typeface typeface = Typeface.SANS_SERIF;

        @Override
        public void onCreate(SurfaceHolder holder) {
            if (Build.MODEL.startsWith("sdk_") || Build.FINGERPRINT.contains("/sdk_")) {
                emulatorMode = true;
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

            mainDial = new WatchDial(this);
            mainDial.radius = 1f;
            mainDial.centerX = 0.0f;
            mainDial.centerY = 0.0f;
            mainDial.ticks1 = 12;
            mainDial.ticks2 = 60;
            mainDial.ticks3 = 300;
            mainDial.tickOuter = 0.99f;
            mainDial.tickInner1 = 0.90f;
            mainDial.tickInner2 = 0.93f;
            mainDial.tickInner3 = 0.96f;
            mainDial.tickWidth1 = 0.01f;
            mainDial.tickWidth2 = 0.005f;
            mainDial.tickWidth3 = 0.0025f;
            mainDial.nonAmbientOnly = false;
            mainDial.circle1 = 0.99f;
            mainDial.circle2 = 0.96f;
            mainDial.circleWidth = 0.0025f;

            subDial1 = new WatchDial(this);
            subDial1.radius = 0.3f;
            subDial1.centerX = 0f;
            subDial1.centerY = -0.25f;
            subDial1.ticks1 = 10;
            subDial1.ticks2 = 50;
            subDial1.tickOuter = 1f;
            subDial1.tickInner1 = 0.80f;
            subDial1.tickInner2 = 0.90f;
            subDial1.tickWidth1 = 0.005f;
            subDial1.tickWidth2 = 0.0025f;
            subDial1.nonAmbientOnly = true;
            subDial1.circle1 = 1f;
            subDial1.circle2 = 0.90f;
            subDial1.circleWidth = 0.0025f;
            subDial1.addText(0.0f, "0");
            subDial1.addText(0.2f, "2");
            subDial1.addText(0.4f, "4");
            subDial1.addText(0.6f, "6");
            subDial1.addText(0.8f, "8");

            subDial2 = new WatchDial(this);
            subDial2.radius = 0.3f;
            subDial2.centerX = -0.25f;
            subDial2.centerY = 0f;
            subDial2.ticks1 = 12;
            subDial2.ticks2 = 60;
            subDial2.tickOuter = 1f;
            subDial2.tickInner1 = 0.80f;
            subDial2.tickInner2 = 0.90f;
            subDial2.tickWidth1 = 0.005f;
            subDial2.tickWidth2 = 0.0025f;
            subDial2.nonAmbientOnly = true;
            subDial2.circle1 = 1f;
            subDial2.circle2 = 0.9f;
            subDial2.circleWidth = 0.0025f;
            subDial2.addText(0.00f, "12");
            subDial2.addText(0.25f, "3");
            subDial2.addText(0.50f, "6");
            subDial2.addText(0.75f, "9");

            subDial3 = new WatchDial(this);
            subDial3.radius = 0.3f;
            subDial3.centerX = 0f;
            subDial3.centerY = 0.25f;
            subDial3.ticks1 = 12;
            subDial3.ticks2 = 60;
            subDial3.tickOuter = 1f;
            subDial3.tickInner1 = 0.80f;
            subDial3.tickInner2 = 0.90f;
            subDial3.tickWidth1 = 0.005f;
            subDial3.tickWidth2 = 0.0025f;
            subDial3.nonAmbientOnly = true;
            subDial3.circle1 = 1f;
            subDial3.circle2 = 0.9f;
            subDial3.circleWidth = 0.0025f;
            subDial3.addText(0.00f, "60");
            subDial3.addText(0.25f, "15");
            subDial3.addText(0.50f, "30");
            subDial3.addText(0.75f, "45");

            subDial4 = new WatchDial(this);
            subDial4.radius = 0.5f;
            subDial4.centerX = 0.125f;
            subDial4.centerY = 0f;
            subDial4.ticks1 = 2;
            subDial4.ticks2 = 10;
            subDial4.ticks3 = 20;
            subDial4.tickOuter = 1f;
            subDial4.tickInner1 = 0.80f;
            subDial4.tickInner2 = 0.86f;
            subDial4.tickInner3 = 0.92f;
            subDial4.tickWidth1 = 0.01f;
            subDial4.tickWidth2 = 0.005f;
            subDial4.tickWidth3 = 0.0025f;
            subDial4.nonAmbientOnly = false;
            subDial4.startAngle = 150f;
            subDial4.endAngle = 30f;
            subDial4.excludeTicksFrom = 0.4f;
            subDial4.excludeTicksTo = 0.6f;
            subDial4.circle1 = 1f;
            subDial4.circle2 = 0.92f;
            subDial4.circleWidth = 0.0025f;
            subDial4.addText(0.00f, "0%");
            subDial4.addText(1.00f, "100%");

            chronographSecondFractionHand = new WatchHand(subDial1);
            chronographSecondFractionHand.color = mSecondHandColor;
            chronographSecondFractionHand.nonAmbientOnly = true;
            chronographSecondFractionHand.length = 0.9f;
            chronographSecondFractionHand.width = 0.02f;

            chronographSecondHand = new WatchHand(subDial3);
            chronographSecondHand.color = mSecondHandColor;
            chronographSecondHand.nonAmbientOnly = true;
            chronographSecondHand.length = 0.9f;
            chronographSecondHand.width = 0.02f;

            chronographMinuteHand = new WatchHand(subDial2);
            chronographMinuteHand.color = mMinuteHandColor;
            chronographMinuteHand.nonAmbientOnly = true;
            chronographMinuteHand.hasArrowHead = true;
            chronographMinuteHand.length = 0.8f;
            chronographMinuteHand.width = 0.02f;

            chronographHourHand = new WatchHand(subDial2);
            chronographHourHand.color = mHourHandColor;
            chronographHourHand.nonAmbientOnly = true;
            chronographHourHand.hasArrowHead = true;
            chronographHourHand.length = 0.8f * 0.7f;
            chronographHourHand.width = 0.02f;

            secondHand = new WatchHand(mainDial);
            secondHand.color = mSecondHandColor;
            secondHand.nonAmbientOnly = true;
            secondHand.length = 0.95f;
            secondHand.width = 0.02f;

            minuteHand = new WatchHand(mainDial);
            minuteHand.color = mMinuteHandColor;
            minuteHand.nonAmbientOnly = false;
            minuteHand.hasArrowHead = true;
            minuteHand.length = 0.9f;
            minuteHand.width = 0.04f;

            hourHand = new WatchHand(mainDial);
            hourHand.color = mHourHandColor;
            hourHand.nonAmbientOnly = false;
            hourHand.hasArrowHead = true;
            hourHand.length = 0.9f * 0.7f;
            hourHand.width = 0.04f;

            batteryHand = new WatchHand(subDial4);
            batteryHand.color = mSecondHandColor;
            batteryHand.nonAmbientOnly = false;
            batteryHand.hasArrowHead = true;
            batteryHand.length = 0.9f;
            batteryHand.width = 0.02f;

            clearIdle();
            updateDials();
            updateHands();

            setCustomTimeout(20);
        }

        private void updateDials() {
            mainDial.update();
            subDial1.update();
            subDial2.update();
            subDial3.update();
            subDial4.update();
        }

        private void updateHands() {
            hourHand.update();
            minuteHand.update();
            secondHand.update();
            chronographSecondFractionHand.update();
            chronographSecondHand.update();
            chronographMinuteHand.update();
            chronographHourHand.update();
            batteryHand.update();
        }

        private void setUpdateRate() {
            if (putChronographSecondsOnSubdial) {
                if (stopwatchRunning) {
                    updateRateMs = 50;
                } else {
                    updateRateMs = 200;
                }
            } else {
                if (stopwatchRunning) {
                    updateRateMs = 50;
                } else {
                    updateRateMs = 1000;
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

            mDiameter = Math.min(width, height);
            mRadius = mDiameter / 2f;
            mCenterX = width / 2f;
            mCenterY = height / 2f;
            mWidth = width;
            mHeight = height;

            mDayDateTextPaint = new Paint();
            mDayDateTextPaint.setAntiAlias(true);
            mDayDateTextPaint.setTextSize(mDiameter * DAY_DATE_TEXT_SIZE);
            mDayDateTextPaint.setColor(Color.BLACK);
            mDayDateTextPaint.setTypeface(typeface);
            mDayDateTextPaint.setTextAlign(Paint.Align.CENTER);

            updateDials();
            updateHands();

            initBackgroundBitmap();
            initAmbientBackgroundBitmap();

            if (!mAmbient) {
                clearIdle();
            }
        }

        private void initBackgroundBitmap() {
            mBackgroundBitmap = Bitmap.createBitmap(mWidth, mHeight, Bitmap.Config.ARGB_8888);
            Canvas backgroundCanvas = new Canvas(mBackgroundBitmap);
            drawClockDial(backgroundCanvas, false);
            mainDial.draw(backgroundCanvas, false);
            subDial1.draw(backgroundCanvas, false);
            subDial2.draw(backgroundCanvas, false);
            subDial3.draw(backgroundCanvas, false);
            subDial4.draw(backgroundCanvas, false);
        }

        private void initAmbientBackgroundBitmap() {
            mAmbientBackgroundBitmap = Bitmap.createBitmap(mWidth, mHeight, Bitmap.Config.ARGB_8888);
            Canvas backgroundCanvas = new Canvas(mAmbientBackgroundBitmap);
            drawClockDial(backgroundCanvas, true);
            mainDial.draw(backgroundCanvas, true);
            subDial1.draw(backgroundCanvas, true);
            subDial2.draw(backgroundCanvas, true);
            subDial3.draw(backgroundCanvas, true);
            subDial4.draw(backgroundCanvas, true);
        }

        private final float DAY_DATE_TEXT_SIZE = 0.0625f;
        private final float DAY_DATE_OUTER = 0.87f;

        private float mDayDateTextSize;
        private float mDayWindowCenterX;
        private float mDateWindowCenterX;
        private float mDayDateWindowTopY;
        private float mDayDateWindowBottomY;
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

            mDayDateTextSize = mDiameter * DAY_DATE_TEXT_SIZE;
            float mDateWindowRightX = mCenterX + mRadius * DAY_DATE_OUTER;
            float mDateWindowLeftX = mDateWindowRightX - maxDateWidth - mDiameter * 0.02f;
            float mDayWindowRightX = mDateWindowLeftX - mDiameter * 0.01f;
            float mDayWindowLeftX = mDayWindowRightX - maxDayWidth - mDiameter * 0.02f;
            mDayWindowCenterX = (mDayWindowLeftX + mDayWindowRightX) / 2f;
            mDateWindowCenterX = (mDateWindowLeftX + mDateWindowRightX) / 2f;
            mDayDateWindowTopY = mCenterY - mDayDateTextSize * 0.6f;
            mDayDateWindowBottomY = mCenterY + mDayDateTextSize * 0.6f;

            Path dialPath = new Path();
            dialPath.addRect(0, 0, width, height, Path.Direction.CW);

            Path dateWindowPath = new Path();
            dateWindowPath.addRect(
                    mDateWindowLeftX, mDayDateWindowTopY,
                    mDateWindowRightX, mDayDateWindowBottomY, Path.Direction.CW
            );

            Path dayWindowPath = new Path();
            dayWindowPath.addRect(
                    mDayWindowLeftX, mDayDateWindowTopY,
                    mDayWindowRightX, mDayDateWindowBottomY, Path.Direction.CW
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
                    if (subDial1.contains(x, y)) {
                        stopwatchButton1();
                        updateTimer();
                    } else if (subDial2.contains(x, y)) {
                        stopwatchButton2();
                        updateTimer();
                    } else if (subDial3.contains(x, y) && emulatorMode) {
                        demoTimeMode = !demoTimeMode;
                        updateTimer();
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

            if (demoTimeMode) {
                mCalendar.setTimeInMillis(
                        // 2019-06-30 10:10:32.500
                        1561903832500L
                );
            } else {
                mCalendar.setTimeInMillis(now);
            }

            drawBackground(canvas);
            drawDate(canvas);
            if (!mAmbient) {
                drawStopwatch(canvas);
            }
            drawWatchFace(canvas);

            if (mWakeLock != null) {
                Log.d(TAG, "wakelock is " + (mWakeLock.isHeld() ? "held" : "not held"));
            }
        }

        private void drawBackground(Canvas canvas) {
            if (mAmbient && (mLowBitAmbient || mBurnInProtection)) {
                canvas.drawBitmap(mAmbientBackgroundBitmap, 0, 0, null);
            } else if (mAmbient) {
                canvas.drawBitmap(mAmbientBackgroundBitmap, 0, 0, null);
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

            canvas.drawText(dayText, mDayWindowCenterX, mCenterY + dayBounds.height() / 2, mDayDateTextPaint);
            canvas.drawText(dateText, mDateWindowCenterX, mCenterY + dateBounds.height() / 2, mDayDateTextPaint);
        }

        private void drawWatchFace(Canvas canvas) {
            int h;
            int m;
            int s;
            int ms;

            h = mCalendar.get(Calendar.HOUR);
            m = mCalendar.get(Calendar.MINUTE);
            s = mCalendar.get(Calendar.SECOND);
            if (putChronographSecondsOnSubdial) {
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

            hourHand.draw(canvas, hoursRotation);
            minuteHand.draw(canvas, minutesRotation);
            secondHand.draw(canvas, secondsRotation);

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
            batteryHand.draw(canvas, batteryRotation);
        }

        private void drawStopwatch(Canvas canvas) {
            long totalMs = getStopwatchTimeMs();

            if (demoTimeMode) {
                totalMs = 500 + 1000 * (32 + 60 * (10 + (60 * 10)));
            }

            final float msRotation = (totalMs % 1000) / 1000f;
            final float sRotation = (totalMs % 60000) / 60000f;
            final float mRotation = (totalMs % 3600000) / 3600000f;
            final float hRotation = (totalMs % 43200000) / 43200000f;

            chronographHourHand.draw(canvas, hRotation);
            chronographMinuteHand.draw(canvas, mRotation);
            chronographSecondHand.draw(canvas, sRotation);
            chronographSecondFractionHand.draw(canvas, msRotation);
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
                long delayMs = updateRateMs - (timeMs % updateRateMs);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        private void drawVerticallyCenteredText(Canvas canvas, String text, float x, float y, Paint paint) {
            final Rect bounds = new Rect();
            paint.getTextBounds(text, 0, text.length(), bounds);
            canvas.drawText(text, x, y - (bounds.bottom + bounds.top) / 2f, paint);
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
         * For keeping the watch face on longer than the standard
         * period of time.
         */

        PowerManager mPowerManager = null;
        PowerManager.WakeLock mWakeLock = null;

        private void acquireWakeLock() {
            if (mPowerManager == null) {
                mPowerManager = (PowerManager)getSystemService(POWER_SERVICE);
            }
            if (mWakeLock == null) {
                mWakeLock = mPowerManager.newWakeLock(
                        PowerManager.FULL_WAKE_LOCK,
                        "PilotWatch::WakeLockTag"
                );
            }
            Log.d(TAG, "acquired wakelock for " + mCustomTimeoutSeconds + " seconds");
            mWakeLock.acquire(mCustomTimeoutSeconds * 1000L);
        }

        private void releaseWakeLock() {
            if (mWakeLock != null) {
                Log.d(TAG, "wakelock is " + (mWakeLock.isHeld() ? "held" : "not held"));
                Log.d(TAG, "released wakelock");
                mWakeLock.release();
            }
        }
    }
}
