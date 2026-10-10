package com.chimeraant.terminal.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

import java.util.Random;

/**
 * A frosty loading animation: ice crystals fading in around an icicle that
 * grows downward, with a gentle shimmer. Used on the launch screen.
 */
public class FrostView extends View {

    private static final int CRYSTAL_COUNT = 26;

    private final Paint crystalPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iciclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path iciclePath = new Path();

    private final float[] crystalX = new float[CRYSTAL_COUNT];
    private final float[] crystalY = new float[CRYSTAL_COUNT];
    private final float[] crystalR = new float[CRYSTAL_COUNT];
    private final float[] crystalPhase = new float[CRYSTAL_COUNT];

    private float progress = 0f;
    private ValueAnimator animator;
    private final Random random = new Random(7L);
    private boolean laidOut = false;

    public FrostView(Context context) {
        this(context, null);
    }

    public FrostView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        crystalPaint.setColor(0xFFB8E8FF);
        crystalPaint.setStyle(Paint.Style.FILL);
        iciclePaint.setColor(0xFF7FD4FF);
        iciclePaint.setStyle(Paint.Style.FILL);
        glowPaint.setColor(0x337FD4FF);
        glowPaint.setStyle(Paint.Style.FILL);
    }

    private void generateCrystals(int width, int height) {
        for (int i = 0; i < CRYSTAL_COUNT; i++) {
            crystalX[i] = random.nextFloat() * width;
            crystalY[i] = random.nextFloat() * height;
            crystalR[i] = dp(1.2f) + random.nextFloat() * dp(2.6f);
            crystalPhase[i] = random.nextFloat();
        }
        laidOut = true;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(2600L);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        generateCrystals(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width == 0 || height == 0) return;
        if (!laidOut) generateCrystals(width, height);

        float centerX = width / 2f;

        // Soft glow behind the icicle.
        canvas.drawCircle(centerX, height * 0.42f, dp(46f), glowPaint);

        // Crystals twinkle in, each on its own phase.
        for (int i = 0; i < CRYSTAL_COUNT; i++) {
            float phase = (progress + crystalPhase[i]) % 1f;
            // Fade in and out again so the field shimmers rather than pulses.
            float alpha = (float) Math.sin(phase * Math.PI);
            crystalPaint.setAlpha((int) (alpha * 190));
            drawCrystal(canvas, crystalX[i], crystalY[i], crystalR[i]);
        }

        // Icicle grows and shrinks with the cycle.
        float grow = (float) Math.abs(Math.sin(progress * Math.PI));
        float icicleWidth = dp(14f);
        float icicleHeight = dp(18f) + grow * dp(34f);
        float top = height * 0.42f - dp(12f);

        iciclePath.reset();
        iciclePath.moveTo(centerX - icicleWidth / 2f, top);
        iciclePath.lineTo(centerX + icicleWidth / 2f, top);
        iciclePath.lineTo(centerX, top + icicleHeight);
        iciclePath.close();
        iciclePaint.setAlpha(210);
        canvas.drawPath(iciclePath, iciclePaint);

        // A falling droplet at the tip.
        float dropPhase = (progress * 2f) % 1f;
        float dropY = top + icicleHeight + dropPhase * dp(22f);
        int dropAlpha = (int) ((1f - dropPhase) * 200);
        crystalPaint.setAlpha(Math.max(0, dropAlpha));
        canvas.drawCircle(centerX, dropY, dp(2.4f), crystalPaint);
    }

    /** Six-armed crystal, drawn as three crossing lines. */
    private void drawCrystal(Canvas canvas, float x, float y, float radius) {
        Paint stroke = crystalPaint;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(dp(0.8f), radius * 0.28f));
        for (int arm = 0; arm < 3; arm++) {
            double angle = arm * Math.PI / 3.0;
            float dx = (float) Math.cos(angle) * radius;
            float dy = (float) Math.sin(angle) * radius;
            canvas.drawLine(x - dx, y - dy, x + dx, y + dy, stroke);
        }
        stroke.setStyle(Paint.Style.FILL);
    }
}
