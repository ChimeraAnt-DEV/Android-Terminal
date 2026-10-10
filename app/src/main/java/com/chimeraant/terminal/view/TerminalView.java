package com.chimeraant.terminal.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.content.ClipboardManager;
import android.content.ClipData;

import androidx.annotation.Nullable;

import com.chimeraant.terminal.core.TerminalEmulator;

import java.nio.charset.StandardCharsets;

/**
 * Canvas-rendered terminal surface. Draws the character grid produced by
 * {@link TerminalEmulator}, handles scroll-back, text selection and forwards
 * input through {@link InputListener}.
 */
public class TerminalView extends View {

    public interface InputListener {
        void onWrite(byte[] data);

        void onResize(int rows, int cols);

        void onRequestKeyboard();
    }

    /** Never let the terminal shrink below a usable grid. */
    private static final int MIN_COLS = 20;
    private static final int MIN_ROWS = 5;

    /** Icy ANSI palette: cool blues and frost tones rather than the usual green. */
    private static final int[] DEFAULT_PALETTE = {
            0xFF0B1119, 0xFFFF6B6B, 0xFF6BE3A0, 0xFFFFC46B,
            0xFF5AA9E6, 0xFFB08CE8, 0xFF5FD6D6, 0xFFC6D6E6,
            0xFF5A6B80, 0xFFFF8F8F, 0xFF8FF0BC, 0xFFFFD48F,
            0xFF7FD4FF, 0xFFC9A8FF, 0xFF8AE8E8, 0xFFF2F8FF
    };

    private int foreground = 0xFFD8E8F6;
    private int background = 0xFF070B12;
    private int cursorColor = 0xFF7FD4FF;
    private int[] palette = DEFAULT_PALETTE.clone();
    private Typeface regularTypeface;
    private Typeface boldTypeface;
    private Typeface italicTypeface;
    private Typeface boldItalicTypeface;

    private TerminalEmulator emulator;
    private InputListener inputListener;

    /** Reused for every glyph so drawing allocates nothing per cell. */
    private final char[] glyphBuffer = new char[1];

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint();
    private final Paint cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectionPaint = new Paint();

    private float cellWidth;
    private float cellHeight;
    private float fontHeight;
    private float baselineOffset;
    private final float textSizeSp;

    private int scrollOffset; // lines scrolled up from the bottom
    private float scrollAccumulator;
    private boolean renderPosted;

    private boolean focused = false;
    private boolean cursorBlinkOn = true;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable blinkRunnable = new Runnable() {
        @Override
        public void run() {
            cursorBlinkOn = !cursorBlinkOn;
            invalidate();
            handler.postDelayed(this, 500);
        }
    };

    private boolean selecting = false;
    private int selStartViewRow = -1;
    private int selStartCol = -1;
    private int selEndViewRow = -1;
    private int selEndCol = -1;

    private final GestureDetector gestureDetector;
    private final ScaleGestureDetector scaleDetector;

    public TerminalView(Context context) {
        this(context, null);
    }

    public TerminalView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        textSizeSp = 14f;
        setFocusable(true);
        setFocusableInTouchMode(true);
        setBackgroundColor(background);

        regularTypeface = TerminalFonts.mono(context);
        boldTypeface = TerminalFonts.bold(context);
        italicTypeface = TerminalFonts.italic(context);
        boldItalicTypeface = TerminalFonts.boldItalic(context);

        textPaint.setTypeface(regularTypeface);
        textPaint.setTextSize(textSizeSp * getResources().getDisplayMetrics().scaledDensity);
        bgPaint.setStyle(Paint.Style.FILL);
        cursorPaint.setColor(cursorColor);
        selectionPaint.setColor(0x667FD4FF);

        recomputeMetrics();

        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                if (!focused) {
                    requestFocus();
                    if (inputListener != null) inputListener.onRequestKeyboard();
                }
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (e1 == null) return false;
                scrollAccumulator += dy;
                while (scrollAccumulator >= cellHeight) {
                    scrollUp(1);
                    scrollAccumulator -= cellHeight;
                }
                while (scrollAccumulator <= -cellHeight) {
                    scrollDown(1);
                    scrollAccumulator += cellHeight;
                }
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                startSelection(e.getX(), e.getY());
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (selecting) {
                    selecting = false;
                    invalidate();
                }
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                clearSelection();
                return true;
            }
        });

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private float lastScale = 1f;

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                return true;
            }
        });
    }

    public void setEmulator(TerminalEmulator emulator) {
        this.emulator = emulator;
        requestLayout();
        invalidate();
    }

    /**
     * Ask for a repaint on the next frame.
     *
     * Session output arrives in many small chunks. Repainting synchronously for
     * each one makes typing feel sluggish, so redraws are coalesced: several
     * chunks that land in the same frame produce a single paint.
     */
    public void requestRender() {
        if (!renderPosted) {
            renderPosted = true;
            postOnAnimation(renderRunnable);
        }
    }

    private final Runnable renderRunnable = () -> {
        renderPosted = false;
        invalidate();
    };

    public TerminalEmulator getEmulator() {
        return emulator;
    }

    public void setInputListener(InputListener listener) {
        this.inputListener = listener;
    }

    public void setColors(int foreground, int background, int cursor, int[] ansiPalette) {
        this.foreground = foreground;
        this.background = background;
        this.cursorColor = cursor;
        if (ansiPalette != null && ansiPalette.length == 16) {
            this.palette = ansiPalette.clone();
        }
        setBackgroundColor(background);
        cursorPaint.setColor(cursorColor);
        invalidate();
    }

    public void setFontSize(float sp) {
        textPaint.setTextSize(sp * getResources().getDisplayMetrics().scaledDensity);
        recomputeMetrics();
        if (emulator != null) {
            updatePtySize();
        }
        invalidate();
    }

    /**
     * Derive the cell grid once, using the widest of the four faces so no
     * style can overflow its cell. A monospace family should agree across all
     * faces; taking the maximum makes that robust when it does not.
     */
    private void recomputeMetrics() {
        Typeface previous = textPaint.getTypeface();
        float maxAdvance = 0f;
        for (Typeface face : new Typeface[]{
                regularTypeface, boldTypeface, italicTypeface, boldItalicTypeface}) {
            if (face == null) continue;
            textPaint.setTypeface(face);
            maxAdvance = Math.max(maxAdvance, textPaint.measureText("W"));
        }
        textPaint.setTypeface(previous);
        cellWidth = maxAdvance > 0f ? (float) Math.ceil(maxAdvance) : 8f;

        Paint.FontMetrics metrics = textPaint.getFontMetrics();
        cellHeight = (float) Math.ceil(metrics.descent - metrics.ascent + metrics.leading + 2f);
        fontHeight = metrics.descent - metrics.ascent;
        baselineOffset = -metrics.ascent;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updatePtySize();
    }

    /** Usable text width, excluding horizontal padding. */
    private int contentWidth() {
        return getWidth() - getPaddingLeft() - getPaddingRight();
    }

    private int contentHeight() {
        return getHeight() - getPaddingTop() - getPaddingBottom();
    }

    /**
     * Columns that fit the current view, or -1 when the view has not been
     * measured yet. Reporting "unknown" matters: before the first layout the
     * width is 0, and a naive division would give a tiny value that wraps every
     * character onto its own line.
     */
    private int computeCols() {
        if (cellWidth <= 0) return -1;
        int available = contentWidth();
        if (available <= 0) return -1;
        int cols = (int) (available / cellWidth);
        return cols < MIN_COLS ? -1 : cols;
    }

    private int computeRows() {
        if (cellHeight <= 0) return -1;
        int available = contentHeight();
        if (available <= 0) return -1;
        int rows = (int) (available / cellHeight);
        return rows < MIN_ROWS ? -1 : rows;
    }

    private void updatePtySize() {
        if (emulator == null) return;
        int cols = computeCols();
        int rows = computeRows();
        // Wait until the view has a real size; resizing to a bogus small grid
        // is what produced the garbled column of words on first launch.
        if (cols < 0 || rows < 0) return;
        if (cols != emulator.getCols() || rows != emulator.getRows()) {
            emulator.resize(cols, rows);
            scrollOffset = 0;
            if (inputListener != null) inputListener.onResize(rows, cols);
        }
    }

    // ------------------------------------------------------------- scrolling

    private int maxScroll() {
        return emulator == null ? 0 : emulator.getScrollbackSize();
    }

    public void scrollUp(int lines) {
        scrollOffset = Math.min(maxScroll(), scrollOffset + lines);
        invalidate();
    }

    public void scrollDown(int lines) {
        scrollOffset = Math.max(0, scrollOffset - lines);
        invalidate();
    }

    public void scrollToBottom() {
        scrollOffset = 0;
        invalidate();
    }

    // ------------------------------------------------------------ selection

    private void startSelection(float x, float y) {
        if (emulator == null) return;
        int[] cell = pointToCell(x, y);
        if (cell == null) return;
        selecting = true;
        selStartViewRow = cell[0];
        selStartCol = cell[1];
        selEndViewRow = cell[0];
        selEndCol = cell[1];
        invalidate();
    }

    private void clearSelection() {
        selecting = false;
        invalidate();
    }

    private int[] pointToCell(float x, float y) {
        if (emulator == null || cellWidth <= 0) return null;
        int col = (int) ((x - getPaddingLeft()) / cellWidth);
        int rowOnScreen = (int) ((y - getPaddingTop()) / cellHeight);
        col = Math.max(0, Math.min(emulator.getCols() - 1, col));
        int viewRow = rowAtScreen(rowOnScreen);
        return new int[]{viewRow, col};
    }

    private int rowAtScreen(int rowOnScreen) {
        int totalViewRows = emulator.getRows() + emulator.getScrollbackSize();
        int firstVisible = totalViewRows - emulator.getRows() - scrollOffset;
        return Math.max(0, firstVisible + rowOnScreen);
    }

    public String getSelectionText() {
        if (!selecting || emulator == null) return "";
        return emulator.getSelectedText(selStartViewRow, selStartCol, selEndViewRow, selEndCol);
    }

    public void copySelection() {
        String text = getSelectionText();
        if (text.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("terminal", text));
        }
        clearSelection();
    }

    public void pasteFromClipboard() {
        if (emulator == null) return;
        ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) return;
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return;
        CharSequence text = clip.getItemAt(0).coerceToText(getContext());
        if (text == null) return;
        String content = text.toString().replace("\n", "\r");
        byte[] data = emulator.isBracketedPaste()
                ? ("\u001B[200~" + content + "\u001B[201~").getBytes(StandardCharsets.UTF_8)
                : content.getBytes(StandardCharsets.UTF_8);
        if (inputListener != null) {
            inputListener.onWrite(data);
        }
        scrollToBottom();
    }

    public void sendText(String text) {
        if (inputListener != null) {
            inputListener.onWrite(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    public void sendKey(KeyEvent event) {
        if (emulator == null) return;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            byte[] seq = emulator.keyToBytes(event.getKeyCode(), event.getMetaState());
            if (seq != null && inputListener != null) {
                inputListener.onWrite(seq);
                scrollToBottom();
            }
        }
    }

    // ----------------------------------------------------------- input events

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        if (selecting && event.getAction() == MotionEvent.ACTION_MOVE) {
            if (emulator != null) {
                int[] cell = pointToCell(event.getX(), event.getY());
                if (cell != null) {
                    selEndViewRow = cell[0];
                    selEndCol = cell[1];
                    invalidate();
                }
            }
            return true;
        }
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (emulator != null) {
            byte[] seq = emulator.keyToBytes(keyCode, event.getMetaState());
            if (seq != null && inputListener != null) {
                inputListener.onWrite(seq);
                scrollToBottom();
                return true;
            }
            int unicode = event.getUnicodeChar(event.getMetaState());
            if (unicode != 0 && inputListener != null) {
                String s = new String(Character.toChars(unicode));
                inputListener.onWrite(s.getBytes(StandardCharsets.UTF_8));
                scrollToBottom();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        // TYPE_NULL tells the system "this view takes no text", and most
        // keyboards then refuse to show at all. A normal text type plus
        // NO_SUGGESTIONS keeps the keyboard on screen while suppressing the
        // autocorrect bar, which would otherwise fight with shell input.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE
                | EditorInfo.IME_FLAG_NO_FULLSCREEN
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_ENTER_ACTION;
        outAttrs.initialCapsMode = 0;
        return new TerminalInputConnection(this, false);
    }

    // -------------------------------------------------------------- rendering

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(background);
        if (emulator == null) {
            return;
        }

        int cols = emulator.getCols();
        int rows = emulator.getRows();
        int totalViewRows = rows + emulator.getScrollbackSize();
        int firstVisible = totalViewRows - rows - scrollOffset;

        for (int screenRow = 0; screenRow < rows; screenRow++) {
            int viewRow = firstVisible + screenRow;
            TerminalEmulator.ScreenLine line = emulator.getLine(viewRow);
            float top = getPaddingTop() + screenRow * cellHeight;
            if (line == null) continue;

            // A line captured at a different width can still be shorter than
            // the current column count; never index past its end.
            final int lineLen = Math.min(line.chars.length, line.styles.length);

            // Background pass: coalesce adjacent cells that share a background.
            int runStart = 0;
            int runBg = Integer.MIN_VALUE;
            for (int col = 0; col <= cols; col++) {
                int bg = col < lineLen ? cellBackground(line, col) : Integer.MIN_VALUE + 1;
                if (col == 0) {
                    runStart = 0;
                    runBg = bg;
                } else if (bg != runBg) {
                    if (runBg != background) {
                        bgPaint.setColor(runBg);
                        canvas.drawRect(getPaddingLeft() + runStart * cellWidth, top,
                                getPaddingLeft() + col * cellWidth, top + cellHeight, bgPaint);
                    }
                    runStart = col;
                    runBg = bg;
                }
            }

            // Glyph pass.
            float x = getPaddingLeft();
            for (int col = 0; col < cols; col++) {
                if (col >= lineLen) {
                    x = getPaddingLeft() + (col + 1) * cellWidth;
                    continue;
                }
                int ch = line.chars[col];
                long style = line.styles[col];
                if (ch != 0 && ch != 0x200B) {
                    glyphBuffer[0] = (char) ch;
                    textPaint.setColor(foregroundColorFor(style));
                    boolean bold = (style & TerminalEmulator.ATTR_BOLD) != 0;
                    boolean italic = (style & TerminalEmulator.ATTR_ITALIC) != 0;
                    // Use the real bold/italic faces instead of faking them.
                    textPaint.setTypeface(typefaceFor(bold, italic));
                    textPaint.setFakeBoldText(false);
                    textPaint.setUnderlineText((style & TerminalEmulator.ATTR_UNDERLINE) != 0);
                    textPaint.setStrikeThruText((style & TerminalEmulator.ATTR_STRIKE) != 0);
                    textPaint.setTextSkewX(0f);
                    // Grid is authoritative: every cell is exactly cellWidth
                    // wide. A glyph wider than its cell is scaled to fit rather
                    // than allowed to spill and collide with the next one.
                    textPaint.setTextScaleX(1f);
                    float advance = textPaint.measureText(glyphBuffer, 0, 1);
                    if (advance > cellWidth && advance > 0f) {
                        textPaint.setTextScaleX(cellWidth / advance);
                    }
                    canvas.drawText(glyphBuffer, 0, 1, x, top + baselineOffset, textPaint);
                    textPaint.setTextScaleX(1f);
                    x += cellWidth;
                } else {
                    x += cellWidth;
                }
            }

            // Selection highlight.
            if (selecting) {
                drawSelectionForRow(canvas, screenRow, viewRow, top);
            }
        }

        // Cursor.
        if (focused && cursorBlinkOn && scrollOffset == 0 && emulator.isCursorVisible()) {
            float cx = getPaddingLeft() + emulator.getCursorCol() * cellWidth;
            float cy = getPaddingTop() + emulator.getCursorRow() * cellHeight;
            cursorPaint.setColor(cursorColor);
            switch (emulator.getCursorShape()) {
                case 3:
                    canvas.drawRect(cx, cy, cx + Math.max(2f, cellWidth / 8f), cy + cellHeight, cursorPaint);
                    break;
                case 4:
                    canvas.drawRect(cx, cy + cellHeight - Math.max(2f, cellHeight / 8f),
                            cx + cellWidth, cy + cellHeight, cursorPaint);
                    break;
                default:
                    cursorPaint.setAlpha(150);
                    canvas.drawRect(cx, cy, cx + cellWidth, cy + cellHeight, cursorPaint);
                    cursorPaint.setAlpha(255);
            }
        }

        // Scrollbar.
        if (emulator.getScrollbackSize() > 0) {
            float trackH = getHeight() - getPaddingTop() - getPaddingBottom();
            float thumbH = Math.max(30f, trackH * (rows / (float) totalViewRows));
            float thumbY = getPaddingTop()
                    + (trackH - thumbH) * (scrollOffset / (float) Math.max(1, emulator.getScrollbackSize()));
            Paint sb = new Paint();
            sb.setColor(0x55FFFFFF);
            canvas.drawRoundRect(new RectF(getWidth() - 8f, thumbY, getWidth() - 2f, thumbY + thumbH),
                    4f, 4f, sb);
        }
    }

    private void drawSelectionForRow(Canvas canvas, int screenRow, int viewRow, float top) {
        int minRow = Math.min(selStartViewRow, selEndViewRow);
        int maxRow = Math.max(selStartViewRow, selEndViewRow);
        if (viewRow < minRow || viewRow > maxRow) return;
        int fromCol;
        int toCol;
        if (selStartViewRow <= selEndViewRow) {
            fromCol = (viewRow == selStartViewRow) ? selStartCol : 0;
            toCol = (viewRow == selEndViewRow) ? selEndCol : emulator.getCols() - 1;
        } else {
            fromCol = (viewRow == selEndViewRow) ? selEndCol : 0;
            toCol = (viewRow == selStartViewRow) ? selStartCol : emulator.getCols() - 1;
        }
        if (fromCol > toCol) {
            int t = fromCol;
            fromCol = toCol;
            toCol = t;
        }
        canvas.drawRect(getPaddingLeft() + fromCol * cellWidth, top,
                getPaddingLeft() + (toCol + 1) * cellWidth, top + cellHeight, selectionPaint);
    }

    private Typeface typefaceFor(boolean bold, boolean italic) {
        if (bold && italic) return boldItalicTypeface;
        if (bold) return boldTypeface;
        if (italic) return italicTypeface;
        return regularTypeface;
    }

    private int cellBackground(TerminalEmulator.ScreenLine line, int col) {
        long style = line.styles[col];
        if ((style & TerminalEmulator.ATTR_REVERSE) != 0) {
            return baseForeground(style);
        }
        return baseBackground(style);
    }

    private int foregroundColorFor(long style) {
        if ((style & TerminalEmulator.ATTR_REVERSE) != 0) {
            return baseBackground(style);
        }
        return baseForeground(style);
    }

    private int baseForeground(long style) {
        int type = TerminalEmulator.fgType(style);
        int value = TerminalEmulator.fgValue(style);
        if (type == 1 && value >= 0 && value < 16) {
            int c = palette[value];
            if ((style & TerminalEmulator.ATTR_BOLD) != 0 && value < 8) {
                c = palette[value + 8];
            }
            return c;
        } else if (type == 2) {
            return 0xFF000000 | value;
        }
        return foreground;
    }

    private int baseBackground(long style) {
        int type = TerminalEmulator.bgType(style);
        int value = TerminalEmulator.bgValue(style);
        if (type == 1 && value >= 0 && value < 16) {
            return palette[value];
        } else if (type == 2) {
            return 0xFF000000 | value;
        }
        return background;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        handler.post(blinkRunnable);
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(blinkRunnable);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, @Nullable android.graphics.Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        focused = gainFocus;
        invalidate();
    }
}
