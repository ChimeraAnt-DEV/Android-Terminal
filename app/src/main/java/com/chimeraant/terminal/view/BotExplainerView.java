package com.chimeraant.terminal.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

/**
 * A small floating assistant in the top right corner.
 *
 * It shows a plain-English explanation of the command under the cursor and
 * types that text out one character at a time, with a gently bobbing head so it
 * reads as an assistant rather than a static tooltip.
 */
public class BotExplainerView extends View {

    private final Paint panelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint botPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint eyePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path panelPath = new Path();
    private final RectF panelRect = new RectF();

    private String title = "";
    private String fullText = "";
    private String shownText = "";

    private float bob = 0f;
    private ValueAnimator bobAnimator;
    private ValueAnimator typingAnimator;
    private boolean caretOn = true;
    private ValueAnimator caretAnimator;

    private final float density;

    public BotExplainerView(Context context) {
        this(context, null);
    }

    public BotExplainerView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;

        panelPaint.setColor(0xF00D1620);
        panelPaint.setStyle(Paint.Style.FILL);
        borderPaint.setColor(0xFF7FD4FF);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1f));
        botPaint.setColor(0xFF7FD4FF);
        botPaint.setStyle(Paint.Style.FILL);
        eyePaint.setColor(0xFF07121C);
        eyePaint.setStyle(Paint.Style.FILL);
        titlePaint.setColor(0xFFB8E8FF);
        titlePaint.setTextSize(sp(11f));
        titlePaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        textPaint.setColor(0xFFD8E8F6);
        textPaint.setTextSize(sp(12f));
        cursorPaint.setColor(0xFFB8E8FF);

        setClickable(false);
        setFocusable(false);
    }

    private float dp(float value) {
        return value * density;
    }

    private float sp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                getResources().getDisplayMetrics());
    }

    /** Show an explanation, typing it out from the start. */
    public void explain(String commandName, String summary) {
        String newTitle = commandName == null || commandName.isEmpty()
                ? "Chimera bot"
                : commandName;
        String newText = summary == null || summary.isEmpty()
                ? "Type a command and I will tell you what it does."
                : summary;
        boolean sameContent = newTitle.equals(title) && newText.equals(fullText);
        title = newTitle;
        fullText = newText;
        if (sameContent) {
            setVisibility(VISIBLE);
            return;
        }
        setVisibility(VISIBLE);
        startTyping();
    }

    public void hide() {
        setVisibility(GONE);
        stopAnimators();
    }

    private void startTyping() {
        stopTyping();
        shownText = "";
        typingAnimator = ValueAnimator.ofInt(0, fullText.length());
        // Roughly 45 characters per second, so it reads as typing.
        typingAnimator.setDuration(Math.max(240L, fullText.length() * 22L));
        typingAnimator.setInterpolator(new LinearInterpolator());
        typingAnimator.addUpdateListener(animation -> {
            int count = (int) animation.getAnimatedValue();
            if (count > fullText.length()) count = fullText.length();
            shownText = fullText.substring(0, count);
            invalidate();
        });
        typingAnimator.start();
        ensureCaretAnimation();
        requestLayout();
    }

    private void stopTyping() {
        if (typingAnimator != null) {
            typingAnimator.cancel();
            typingAnimator = null;
        }
    }

    private void ensureCaretAnimation() {
        if (caretAnimator != null) return;
        caretAnimator = ValueAnimator.ofFloat(0f, 1f);
        caretAnimator.setDuration(900L);
        caretAnimator.setRepeatCount(ValueAnimator.INFINITE);
        caretAnimator.setRepeatMode(ValueAnimator.RESTART);
        caretAnimator.setInterpolator(new LinearInterpolator());
        caretAnimator.addUpdateListener(a -> {
            float progress = (float) a.getAnimatedValue();
            boolean on = progress < 0.5f;
            if (on != caretOn) {
                caretOn = on;
                invalidate();
            }
        });
        caretAnimator.start();
    }

    private void ensureBobAnimation() {
        if (bobAnimator != null) return;
        bobAnimator = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
        bobAnimator.setDuration(2200L);
        bobAnimator.setRepeatCount(ValueAnimator.INFINITE);
        bobAnimator.setInterpolator(new LinearInterpolator());
        bobAnimator.addUpdateListener(a -> {
            bob = (float) Math.sin((float) a.getAnimatedValue());
            invalidate();
        });
        bobAnimator.start();
    }

    private void stopAnimators() {
        stopTyping();
        if (bobAnimator != null) {
            bobAnimator.cancel();
            bobAnimator = null;
        }
        if (caretAnimator != null) {
            caretAnimator.cancel();
            caretAnimator = null;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ensureBobAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnimators();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int maxWidth = (int) dp(230f);
        int available = MeasureSpec.getSize(widthMeasureSpec);
        int width = available > 0 ? Math.min(maxWidth, available) : maxWidth;

        textPaint.setTextSize(sp(12f));
        int textWidth = width - (int) dp(46f);
        float lineHeight = textPaint.getFontMetrics().descent
                - textPaint.getFontMetrics().ascent + dp(2f);
        int lines = Math.max(1, (int) Math.ceil(
                textPaint.measureText(fullText) / Math.max(1, textWidth)));
        int height = (int) (dp(18f) + lines * lineHeight + dp(12f));
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float radius = dp(12f);
        panelRect.set(dp(1f), dp(1f), getWidth() - dp(1f), getHeight() - dp(1f));
        panelPath.reset();
        panelPath.addRoundRect(panelRect, radius, radius, Path.Direction.CW);

        canvas.drawPath(panelPath, panelPaint);
        canvas.drawPath(panelPath, borderPaint);

        // Head: a rounded box that bobs up and down, with two eyes.
        float headSize = dp(26f);
        float headLeft = dp(10f);
        float headTop = dp(10f) + bob * dp(2f);
        RectF head = new RectF(headLeft, headTop, headLeft + headSize, headTop + headSize);
        canvas.drawRoundRect(head, dp(8f), dp(8f), botPaint);

        // Antenna, which also sways with the bob.
        float antennaBaseX = head.centerX();
        float antennaBaseY = headTop;
        float antennaTipX = antennaBaseX + bob * dp(3f);
        float antennaTipY = antennaBaseY - dp(7f);
        canvas.drawLine(antennaBaseX, antennaBaseY, antennaTipX, antennaTipY, botPaint);
        canvas.drawCircle(antennaTipX, antennaTipY, dp(2f), botPaint);

        // Eyes blink when the bob crosses zero.
        float eyeRadius = dp(2.6f);
        float eyeY = head.centerY() - bob * dp(1f);
        canvas.drawCircle(head.centerX() - dp(5f), eyeY, eyeRadius, eyePaint);
        canvas.drawCircle(head.centerX() + dp(5f), eyeY, eyeRadius, eyePaint);

        // Text area.
        float textLeft = dp(44f);
        float titleBaseline = dp(20f);
        canvas.drawText(title, textLeft, titleBaseline, titlePaint);

        float lineHeight = textPaint.getFontMetrics().descent
                - textPaint.getFontMetrics().ascent + dp(2f);
        float y = titleBaseline + lineHeight;
        float maxWidth = getWidth() - textLeft - dp(10f);

        int index = 0;
        while (index < shownText.length()) {
            int count = textPaint.breakText(shownText, index, shownText.length(),
                    true, maxWidth, null);
            if (count <= 0) break;
            String lineText = shownText.substring(index, index + count);
            canvas.drawText(lineText, textLeft, y, textPaint);
            index += count;
            y += lineHeight;
            if (y > getHeight() - dp(4f)) break;
        }

        // Blinking caret at the end of the typed text.
        if (caretOn && index >= shownText.length()) {
            float caretX = textLeft + textPaint.measureText(
                    shownText.substring(Math.max(0, index - lastLineLength(shownText, maxWidth))));
            float caretY = y - lineHeight;
            canvas.drawRect(caretX + dp(1f), caretY - dp(10f),
                    caretX + dp(2.5f), caretY + dp(1f), cursorPaint);
        }
    }

    private int lastLineLength(String text, float maxWidth) {
        int index = 0;
        int lastCount = 0;
        while (index < text.length()) {
            int count = textPaint.breakText(text, index, text.length(), true, maxWidth, null);
            if (count <= 0) break;
            lastCount = count;
            index += count;
        }
        return lastCount;
    }
}
