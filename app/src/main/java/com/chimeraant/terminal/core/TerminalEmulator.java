package com.chimeraant.terminal.core;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * A pragmatic VT100/xterm-compatible terminal emulator.
 *
 * It owns the character grid, scrollback, alternate screen and the escape
 * sequence state machine. Rendering is intentionally left to the view layer;
 * this class only mutates state and reports events through {@link Listener}.
 */
public class TerminalEmulator {

    public interface Listener {
        void onTitleChanged(String title);

        void onBell();

        void onClipboardRequest(String selection);

        void onScreenChanged();

        default void onReply(String data) {}
    }

    // Attribute bits inside the style long.
    public static final long ATTR_BOLD = 1L << 52;
    public static final long ATTR_DIM = 1L << 53;
    public static final long ATTR_ITALIC = 1L << 54;
    public static final long ATTR_UNDERLINE = 1L << 55;
    public static final long ATTR_BLINK = 1L << 56;
    public static final long ATTR_REVERSE = 1L << 57;
    public static final long ATTR_HIDDEN = 1L << 58;
    public static final long ATTR_STRIKE = 1L << 59;

    private static final int COLOR_DEFAULT = 0;
    private static final int COLOR_PALETTE = 1;
    private static final int COLOR_RGB = 2;

    private static final long DEFAULT_STYLE = (long) COLOR_DEFAULT
            | ((long) COLOR_DEFAULT << 26);

    private static final int MAX_PARAMS = 32;

    private int cols;
    private int rows;

    private int[] chars;
    private long[] styles;

    private int cursorRow;
    private int cursorCol;
    private boolean cursorVisible = true;
    private int cursorShape = 1; // 0 block, 1 block, 2 underline, 3 bar

    private int savedRow;
    private int savedCol;
    private long savedStyle = DEFAULT_STYLE;

    private long currentStyle = DEFAULT_STYLE;

    private int scrollTop;
    private int scrollBottom;

    private boolean originMode = false;
    private boolean insertMode = false;
    private boolean autoWrap = true;
    private boolean pendingWrap = false;
    private boolean appCursorKeys = false;
    private boolean appKeypad = false;
    private boolean bracketedPaste = false;
    private boolean cursorKeysApp;

    private final ArrayDeque<ScreenLine> scrollback = new ArrayDeque<>();
    private int scrollbackMax = 5000;

    private ScreenLine[] altChars;
    private boolean altActive = false;
    private int altSavedRow;
    private int altSavedCol;
    private long altSavedStyle;

    private String title = "";

    private final Listener listener;

    // Parser state.
    private int state = STATE_GROUND;
    private final int[] params = new int[MAX_PARAMS];
    private int paramCount;
    private int currentParam;
    private boolean paramHasValue;
    private boolean privateMarker;   // '?' '>' '<' '='
    private int privateMarkerChar;
    private int intermediate;
    private final StringBuilder stringBuilder = new StringBuilder();
    private int charsets0 = 'B';
    private int charsets1 = 'B';
    private boolean g1Selected = false;

    private final ByteArrayOutputStream utf8Buffer = new ByteArrayOutputStream(4);

    private static final int STATE_GROUND = 0;
    private static final int STATE_ESC = 1;
    private static final int STATE_CSI_ENTRY = 2;
    private static final int STATE_CSI_PARAM = 3;
    private static final int STATE_CSI_INTERMEDIATE = 4;
    private static final int STATE_OSC = 5;
    private static final int STATE_ESC_INTERMEDIATE = 6;
    private static final int STATE_DCS = 7;
    private static final int STATE_CHARSET = 8;

    public static class ScreenLine {
        public final int[] chars;
        public final long[] styles;

        public ScreenLine(int[] chars, long[] styles) {
            this.chars = chars;
            this.styles = styles;
        }
    }

    public TerminalEmulator(int cols, int rows, Listener listener) {
        this.listener = listener;
        resize(cols, rows);
    }

    public synchronized void resize(int newCols, int newRows) {
        int oldCols = this.cols;
        int oldRows = this.rows;
        int[] oldChars = this.chars;
        long[] oldStyles = this.styles;

        this.cols = Math.max(2, newCols);
        this.rows = Math.max(2, newRows);
        this.chars = new int[this.cols * this.rows];
        this.styles = new long[this.cols * this.rows];
        clearAll();

        if (oldChars != null) {
            int copyRows = Math.min(oldRows, this.rows);
            int copyCols = Math.min(oldCols, this.cols);
            for (int r = 0; r < copyRows; r++) {
                System.arraycopy(oldChars, r * oldCols, this.chars, r * this.cols, copyCols);
                System.arraycopy(oldStyles, r * oldCols, this.styles, r * this.cols, copyCols);
            }
        }

        // Scrollback and alternate-screen lines keep the width they were
        // captured at. Re-normalise them to the new column count: a later,
        // wider layout would otherwise index past the end of a stored line.
        if (oldCols != this.cols) {
            normaliseScrollbackWidth();
            normaliseAltScreenWidth();
        }

        cursorRow = Math.min(cursorRow, this.rows - 1);
        cursorCol = Math.min(cursorCol, this.cols - 1);
        scrollTop = 0;
        scrollBottom = this.rows - 1;
        pendingWrap = false;
    }

    private void normaliseScrollbackWidth() {
        if (scrollback.isEmpty()) return;
        ArrayDeque<ScreenLine> resized = new ArrayDeque<>(scrollback.size());
        for (ScreenLine line : scrollback) {
            resized.addLast(reflowLine(line));
        }
        scrollback.clear();
        scrollback.addAll(resized);
    }

    private void normaliseAltScreenWidth() {
        if (altChars == null) return;
        for (int r = 0; r < altChars.length; r++) {
            if (altChars[r] != null) {
                altChars[r] = reflowLine(altChars[r]);
            }
        }
    }

    /** Copy a line into arrays sized for the current column count. */
    private ScreenLine reflowLine(ScreenLine line) {
        if (line.chars.length == cols && line.styles.length == cols) {
            return line;
        }
        int[] c = new int[cols];
        long[] s = new long[cols];
        int n = Math.min(line.chars.length, cols);
        System.arraycopy(line.chars, 0, c, 0, n);
        System.arraycopy(line.styles, 0, s, 0, Math.min(line.styles.length, cols));
        for (int i = n; i < cols; i++) {
            s[i] = DEFAULT_STYLE;
        }
        return new ScreenLine(c, s);
    }

    private void clearAll() {
        for (int i = 0; i < chars.length; i++) {
            chars[i] = 0;
            styles[i] = DEFAULT_STYLE;
        }
    }

    public int getCols() {
        return cols;
    }

    public int getRows() {
        return rows;
    }

    public int getCursorRow() {
        return cursorRow;
    }

    public int getCursorCol() {
        return cursorCol;
    }

    public boolean isCursorVisible() {
        return cursorVisible;
    }

    public int getCursorShape() {
        return cursorShape;
    }

    public boolean isAppCursorKeys() {
        return appCursorKeys;
    }

    public boolean isBracketedPaste() {
        return bracketedPaste;
    }

    public String getTitle() {
        return title;
    }

    public int getScrollbackSize() {
        return scrollback.size();
    }

    public synchronized long getCharAt(int viewRow, int col) {
        if (viewRow < 0) {
            return 0;
        }
        if (viewRow < rows) {
            int idx = viewRow * cols + col;
            if (idx < 0 || idx >= chars.length) return 0;
            long c = chars[idx];
            long style = styles[idx];
            return (c & 0x1FFFFFL) | (style << 21);
        }
        int sbIndex = viewRow - rows;
        int i = 0;
        for (ScreenLine line : scrollback) {
            if (i == sbIndex) {
                if (col < 0 || col >= line.chars.length) return 0;
                return (line.chars[col] & 0x1FFFFFL) | (line.styles[col] << 21);
            }
            i++;
        }
        return 0;
    }

    public static int cellChar(long packed) {
        return (int) (packed & 0x1FFFFFL);
    }

    public static long cellStyle(long packed) {
        return packed >>> 21;
    }

    public synchronized ScreenLine getLine(int viewRow) {
        if (viewRow < 0) return null;
        if (viewRow < rows) {
            int[] c = new int[cols];
            long[] s = new long[cols];
            System.arraycopy(chars, viewRow * cols, c, 0, cols);
            System.arraycopy(styles, viewRow * cols, s, 0, cols);
            return new ScreenLine(c, s);
        }
        int sbIndex = viewRow - rows;
        if (sbIndex >= scrollback.size()) return null;
        int i = 0;
        for (ScreenLine line : scrollback) {
            if (i == sbIndex) return line;
            i++;
        }
        return null;
    }

    public synchronized ScreenLine[] snapshot() {
        int total = rows + scrollback.size();
        ScreenLine[] out = new ScreenLine[total];
        int i = 0;
        for (ScreenLine line : scrollback) {
            out[i++] = line;
        }
        for (int r = 0; r < rows; r++) {
            int[] c = new int[cols];
            long[] s = new long[cols];
            System.arraycopy(chars, r * cols, c, 0, cols);
            System.arraycopy(styles, r * cols, s, 0, cols);
            out[i++] = new ScreenLine(c, s);
        }
        return out;
    }

    // ---------------------------------------------------------------- input

    /** Feed raw bytes from the PTY. UTF-8 sequences are reassembled here. */
    public synchronized void process(byte[] data, int length) {
        for (int i = 0; i < length; i++) {
            int b = data[i] & 0xFF;
            if (state == STATE_GROUND && (b < 0x80)) {
                utf8Buffer.reset();
                processChar(b);
                continue;
            }
            if (b < 0x80 && utf8Buffer.size() == 0) {
                processChar(b);
                continue;
            }
            utf8Buffer.write(b);
            int expected = expectedUtf8Length(utf8Buffer.toByteArray());
            if (expected > 0 && utf8Buffer.size() >= expected) {
                int cp = decodeUtf8(utf8Buffer.toByteArray());
                utf8Buffer.reset();
                if (cp >= 0) processChar(cp);
            } else if (expected < 0 || utf8Buffer.size() > 4) {
                utf8Buffer.reset();
            }
        }
    }

    private static int expectedUtf8Length(byte[] b) {
        if (b.length == 0) return -1;
        int first = b[0] & 0xFF;
        if (first < 0x80) return 1;
        if ((first & 0xE0) == 0xC0) return 2;
        if ((first & 0xF0) == 0xE0) return 3;
        if ((first & 0xF8) == 0xF0) return 4;
        return -1;
    }

    private static int decodeUtf8(byte[] b) {
        try {
            return new String(b, "UTF-8").codePointAt(0);
        } catch (Exception e) {
            return -1;
        }
    }

    private void processChar(int c) {
        switch (state) {
            case STATE_GROUND:
                ground(c);
                break;
            case STATE_ESC:
                escape(c);
                break;
            case STATE_ESC_INTERMEDIATE:
                if (c >= 0x20 && c <= 0x2F) {
                    intermediate = c;
                } else {
                    escapeFinal(c);
                    state = STATE_GROUND;
                }
                break;
            case STATE_CSI_ENTRY:
            case STATE_CSI_PARAM:
                csi(c);
                break;
            case STATE_CSI_INTERMEDIATE:
                if (c >= 0x20 && c <= 0x2F) {
                    intermediate = c;
                } else {
                    csiDispatch(c);
                    state = STATE_GROUND;
                }
                break;
            case STATE_OSC:
                osc(c);
                break;
            case STATE_DCS:
                if (c == 0x07) {
                    state = STATE_GROUND;
                } else if (c == 0x1B) {
                    state = STATE_ESC;
                }
                break;
            case STATE_CHARSET:
                if (c == '0' || c == 'B' || c == 'A') {
                    if (g1Selected) {
                        charsets1 = c;
                    } else {
                        charsets0 = c;
                    }
                }
                state = STATE_GROUND;
                break;
            default:
                state = STATE_GROUND;
        }
    }

    private void ground(int c) {
        switch (c) {
            case 0x07:
                if (listener != null) listener.onBell();
                break;
            case 0x08:
                if (cursorCol > 0) cursorCol--;
                pendingWrap = false;
                break;
            case 0x09:
                cursorCol = Math.min(cols - 1, (cursorCol / 8 + 1) * 8);
                pendingWrap = false;
                break;
            case 0x0A:
            case 0x0B:
            case 0x0C:
                lineFeed();
                break;
            case 0x0D:
                cursorCol = 0;
                pendingWrap = false;
                break;
            case 0x0E:
                g1Selected = true;
                break;
            case 0x0F:
                g1Selected = false;
                break;
            case 0x1B:
                state = STATE_ESC;
                break;
            case 0x7F:
                break;
            default:
                if (c < 0x20) {
                    break;
                }
                putChar(c);
        }
    }

    private void escape(int c) {
        switch (c) {
            case '[':
                resetParams();
                state = STATE_CSI_ENTRY;
                break;
            case ']':
                stringBuilder.setLength(0);
                state = STATE_OSC;
                break;
            case 'P':
            case '^':
            case '_':
                state = STATE_DCS;
                break;
            case '(':
                g1Selected = false;
                state = STATE_CHARSET;
                break;
            case ')':
                g1Selected = true;
                state = STATE_CHARSET;
                break;
            case '7':
                saveCursor();
                state = STATE_GROUND;
                break;
            case '8':
                restoreCursor();
                state = STATE_GROUND;
                break;
            case 'D':
                lineFeed();
                state = STATE_GROUND;
                break;
            case 'E':
                cursorCol = 0;
                lineFeed();
                state = STATE_GROUND;
                break;
            case 'M':
                reverseIndex();
                state = STATE_GROUND;
                break;
            case 'c':
                fullReset();
                state = STATE_GROUND;
                break;
            case '=':
                appKeypad = true;
                state = STATE_GROUND;
                break;
            case '>':
                appKeypad = false;
                state = STATE_GROUND;
                break;
            case '\\':
                state = STATE_GROUND;
                break;
            default:
                if (c >= 0x20 && c <= 0x2F) {
                    intermediate = c;
                    state = STATE_ESC_INTERMEDIATE;
                } else {
                    state = STATE_GROUND;
                }
        }
    }

    private void escapeFinal(int c) {
        // Two-character escape sequences with an intermediate byte.
        if (intermediate == '#') {
            if (c == '8') {
                for (int r = 0; r < rows; r++) {
                    for (int col = 0; col < cols; col++) {
                        setCell(r, col, 'E', currentStyle);
                    }
                }
            }
        }
    }

    private void csi(int c) {
        if (c >= '0' && c <= '9') {
            currentParam = currentParam * 10 + (c - '0');
            paramHasValue = true;
            state = STATE_CSI_PARAM;
        } else if (c == ';' || c == ':') {
            pushParam();
            state = STATE_CSI_PARAM;
        } else if (c == '?' || c == '>' || c == '<' || c == '=') {
            privateMarker = true;
            privateMarkerChar = c;
            state = STATE_CSI_PARAM;
        } else if (c >= 0x20 && c <= 0x2F) {
            intermediate = c;
            state = STATE_CSI_INTERMEDIATE;
        } else if (c >= 0x40 && c <= 0x7E) {
            pushParam();
            csiDispatch(c);
            state = STATE_GROUND;
        } else if (c == 0x1B) {
            state = STATE_ESC;
        } else {
            state = STATE_GROUND;
        }
    }

    private void resetParams() {
        paramCount = 0;
        currentParam = 0;
        paramHasValue = false;
        privateMarker = false;
        privateMarkerChar = 0;
        intermediate = 0;
    }

    private void pushParam() {
        if (paramCount < MAX_PARAMS) {
            params[paramCount++] = paramHasValue ? currentParam : 0;
        }
        currentParam = 0;
        paramHasValue = false;
    }

    private int param(int index, int defaultValue) {
        if (index >= paramCount) return defaultValue;
        int v = params[index];
        return v == 0 ? defaultValue : v;
    }

    private int rawParam(int index, int defaultValue) {
        if (index >= paramCount) return defaultValue;
        return params[index];
    }

    private void csiDispatch(int c) {
        switch (c) {
            case 'A': cursorUp(param(0, 1)); break;
            case 'B': cursorDown(param(0, 1)); break;
            case 'C': cursorForward(param(0, 1)); break;
            case 'D': cursorBackward(param(0, 1)); break;
            case 'E': cursorDown(param(0, 1)); cursorCol = 0; break;
            case 'F': cursorUp(param(0, 1)); cursorCol = 0; break;
            case 'G': cursorCol = clamp(param(0, 1) - 1, 0, cols - 1); pendingWrap = false; break;
            case 'H':
            case 'f': {
                int row = param(0, 1) - 1;
                int col = param(1, 1) - 1;
                if (originMode) row += scrollTop;
                cursorRow = clamp(row, 0, rows - 1);
                cursorCol = clamp(col, 0, cols - 1);
                pendingWrap = false;
                break;
            }
            case 'd': cursorRow = clamp(param(0, 1) - 1, 0, rows - 1); pendingWrap = false; break;
            case 'J': eraseDisplay(param(0, 0)); break;
            case 'K': eraseLine(param(0, 0)); break;
            case 'L': insertLines(param(0, 1)); break;
            case 'M': deleteLines(param(0, 1)); break;
            case '@': insertChars(param(0, 1)); break;
            case 'P': deleteChars(param(0, 1)); break;
            case 'X': eraseChars(param(0, 1)); break;
            case 'S': scrollUp(param(0, 1)); break;
            case 'T': scrollDown(param(0, 1)); break;
            case 'b':
                if (cursorCol > 0) {
                    int ch = getCell(cursorRow, cursorCol - 1);
                    int n = param(0, 1);
                    for (int i = 0; i < n; i++) putChar(ch);
                }
                break;
            case 'm': selectGraphicRendition(); break;
            case 'h': setMode(true); break;
            case 'l': setMode(false); break;
            case 'r': {
                int top = param(0, 1) - 1;
                int bottom = param(1, rows) - 1;
                scrollTop = clamp(top, 0, rows - 1);
                scrollBottom = clamp(bottom, 0, rows - 1);
                if (scrollBottom <= scrollTop) scrollBottom = rows - 1;
                if (originMode) {
                    cursorRow = scrollTop;
                    cursorCol = 0;
                } else {
                    cursorRow = 0;
                    cursorCol = 0;
                }
                break;
            }
            case 's': saveCursor(); break;
            case 'u': restoreCursor(); break;
            case 'g':
                break;
            case 'n':
                if (param(0, 0) == 6 && listener != null) {
                    listener.onReply("\u001B[" + (cursorRow + 1) + ";" + (cursorCol + 1) + "R");
                }
                break;
            case 'c':
                if (listener != null) listener.onReply("\u001B[?6c");
                break;
            case 't':
                break;
            case 'p':
                break;
            case 'q':
                if (privateMarker && privateMarkerChar == ' ') {
                    cursorShape = param(0, 1);
                }
                break;
            default:
                break;
        }
    }

    private void setMode(boolean set) {
        if (privateMarker && privateMarkerChar == '?') {
            for (int i = 0; i < paramCount; i++) {
                switch (params[i]) {
                    case 1:
                        appCursorKeys = set;
                        break;
                    case 6:
                        originMode = set;
                        if (set) {
                            cursorRow = scrollTop;
                            cursorCol = 0;
                        } else {
                            cursorRow = 0;
                            cursorCol = 0;
                        }
                        break;
                    case 7:
                        autoWrap = set;
                        break;
                    case 25:
                        cursorVisible = set;
                        break;
                    case 47:
                    case 1047:
                    case 1049:
                        if (set) enterAltScreen();
                        else leaveAltScreen();
                        break;
                    case 1000:
                    case 1002:
                    case 1003:
                    case 1006:
                        // Mouse reporting requested; handled as a no-op for now.
                        break;
                    case 2004:
                        bracketedPaste = set;
                        break;
                    default:
                        break;
                }
            }
        } else {
            for (int i = 0; i < paramCount; i++) {
                switch (params[i]) {
                    case 4:
                        insertMode = set;
                        break;
                    case 20:
                        break;
                    default:
                        break;
                }
            }
        }
    }

    private void selectGraphicRendition() {
        if (paramCount == 0) {
            currentStyle = DEFAULT_STYLE;
            return;
        }
        for (int i = 0; i < paramCount; i++) {
            int p = params[i];
            switch (p) {
                case 0: currentStyle = DEFAULT_STYLE; break;
                case 1: currentStyle |= ATTR_BOLD; break;
                case 2: currentStyle |= ATTR_DIM; break;
                case 3: currentStyle |= ATTR_ITALIC; break;
                case 4: currentStyle |= ATTR_UNDERLINE; break;
                case 5:
                case 6: currentStyle |= ATTR_BLINK; break;
                case 7: currentStyle |= ATTR_REVERSE; break;
                case 8: currentStyle |= ATTR_HIDDEN; break;
                case 9: currentStyle |= ATTR_STRIKE; break;
                case 21:
                case 22: currentStyle &= ~(ATTR_BOLD | ATTR_DIM); break;
                case 23: currentStyle &= ~ATTR_ITALIC; break;
                case 24: currentStyle &= ~ATTR_UNDERLINE; break;
                case 25: currentStyle &= ~ATTR_BLINK; break;
                case 27: currentStyle &= ~ATTR_REVERSE; break;
                case 28: currentStyle &= ~ATTR_HIDDEN; break;
                case 29: currentStyle &= ~ATTR_STRIKE; break;
                case 39: currentStyle = setFg(currentStyle, COLOR_DEFAULT, 0); break;
                case 49: currentStyle = setBg(currentStyle, COLOR_DEFAULT, 0); break;
                case 38:
                    i = parseExtendedColor(i, true);
                    break;
                case 48:
                    i = parseExtendedColor(i, false);
                    break;
                case 58:
                    i = parseExtendedColor(i, false);
                    break;
                default:
                    if (p >= 30 && p <= 37) {
                        currentStyle = setFg(currentStyle, COLOR_PALETTE, p - 30);
                    } else if (p >= 40 && p <= 47) {
                        currentStyle = setBg(currentStyle, COLOR_PALETTE, p - 40);
                    } else if (p >= 90 && p <= 97) {
                        currentStyle = setFg(currentStyle, COLOR_PALETTE, p - 90 + 8);
                    } else if (p >= 100 && p <= 107) {
                        currentStyle = setBg(currentStyle, COLOR_PALETTE, p - 100 + 8);
                    }
            }
        }
    }

    private int parseExtendedColor(int index, boolean foreground) {
        if (index + 1 >= paramCount) return index;
        int mode = params[index + 1];
        if (mode == 5) {
            if (index + 2 >= paramCount) return index + 1;
            int colorIndex = params[index + 2];
            if (foreground) {
                currentStyle = setFg(currentStyle, COLOR_PALETTE, colorIndex);
            } else {
                currentStyle = setBg(currentStyle, COLOR_PALETTE, colorIndex);
            }
            return index + 2;
        } else if (mode == 2) {
            if (index + 4 >= paramCount) return paramCount - 1;
            int r = params[index + 2];
            int g = params[index + 3];
            int b = params[index + 4];
            int rgb = (r << 16) | (g << 8) | b;
            if (foreground) {
                currentStyle = setFg(currentStyle, COLOR_RGB, rgb);
            } else {
                currentStyle = setBg(currentStyle, COLOR_RGB, rgb);
            }
            return index + 4;
        }
        return index + 1;
    }

    private static long setFg(long style, int type, int value) {
        long mask = ~((3L) | (0xFFFFFFL << 2));
        return (style & ~mask) | (type & 3L) | ((value & 0xFFFFFFL) << 2);
    }

    private static long setBg(long style, int type, int value) {
        long mask = ((3L) << 26) | (0xFFFFFFL << 28);
        return (style & ~mask) | ((type & 3L) << 26) | ((value & 0xFFFFFFL) << 28);
    }

    public static int fgType(long style) {
        return (int) (style & 3);
    }

    public static int fgValue(long style) {
        return (int) ((style >>> 2) & 0xFFFFFF);
    }

    public static int bgType(long style) {
        return (int) ((style >>> 26) & 3);
    }

    public static int bgValue(long style) {
        return (int) ((style >>> 28) & 0xFFFFFF);
    }

    private void osc(int c) {
        if (c == 0x07 || c == 0x1B) {
            String payload = stringBuilder.toString();
            handleOsc(payload);
            stringBuilder.setLength(0);
            state = c == 0x1B ? STATE_ESC : STATE_GROUND;
        } else {
            stringBuilder.append((char) c);
        }
    }

    private void handleOsc(String payload) {
        int semi = payload.indexOf(';');
        if (semi < 0) return;
        String code = payload.substring(0, semi);
        String value = payload.substring(semi + 1);
        if ("0".equals(code) || "1".equals(code) || "2".equals(code)) {
            title = value;
            if (listener != null) listener.onTitleChanged(value);
        } else if ("52".equals(code)) {
            // OSC 52 clipboard
            int idx = value.indexOf(';');
            if (idx >= 0 && listener != null) {
                String b64 = value.substring(idx + 1);
                listener.onClipboardRequest(b64);
            }
        }
    }

    // ------------------------------------------------------------ operations

    private void putChar(int c) {
        if (c == '\t') {
            ground(0x09);
            return;
        }
        int width = charWidth(c);
        if (width == 0) {
            return; // Combining marks are dropped for simplicity.
        }
        if (pendingWrap) {
            cursorCol = 0;
            lineFeed();
            pendingWrap = false;
        }
        if (cursorCol >= cols) {
            if (autoWrap) {
                cursorCol = 0;
                lineFeed();
            } else {
                cursorCol = cols - 1;
            }
        }
        if (insertMode) {
            for (int col = cols - 1; col > cursorCol; col--) {
                chars[cursorRow * cols + col] = chars[cursorRow * cols + col - 1];
                styles[cursorRow * cols + col] = styles[cursorRow * cols + col - 1];
            }
        }
        setCell(cursorRow, cursorCol, c, currentStyle);
        if (width == 2 && cursorCol + 1 < cols) {
            setCell(cursorRow, cursorCol + 1, 0x200B, currentStyle);
        }
        cursorCol += width;
        if (cursorCol >= cols) {
            if (autoWrap) {
                cursorCol = cols - 1;
                pendingWrap = true;
            } else {
                cursorCol = cols - 1;
            }
        }
    }

    private void setCell(int row, int col, int c, long style) {
        if (row < 0 || row >= rows || col < 0 || col >= cols) return;
        chars[row * cols + col] = c;
        styles[row * cols + col] = style;
    }

    private int getCell(int row, int col) {
        if (row < 0 || row >= rows || col < 0 || col >= cols) return 0;
        return chars[row * cols + col];
    }

    private void lineFeed() {
        pendingWrap = false;
        if (cursorRow == scrollBottom) {
            scrollRegionUp(1);
        } else if (cursorRow < rows - 1) {
            cursorRow++;
        }
    }

    private void reverseIndex() {
        pendingWrap = false;
        if (cursorRow == scrollTop) {
            scrollRegionDown(1);
        } else if (cursorRow > 0) {
            cursorRow--;
        }
    }

    private void scrollRegionUp(int n) {
        for (int i = 0; i < n; i++) {
            if (scrollTop == 0 && scrollBottom == rows - 1 && !altActive) {
                pushScrollback();
            }
            System.arraycopy(chars, (scrollTop + 1) * cols, chars, scrollTop * cols,
                    (scrollBottom - scrollTop) * cols);
            System.arraycopy(styles, (scrollTop + 1) * cols, styles, scrollTop * cols,
                    (scrollBottom - scrollTop) * cols);
            for (int col = 0; col < cols; col++) {
                chars[scrollBottom * cols + col] = 0;
                styles[scrollBottom * cols + col] = DEFAULT_STYLE;
            }
        }
    }

    private void scrollRegionDown(int n) {
        for (int i = 0; i < n; i++) {
            System.arraycopy(chars, scrollTop * cols, chars, (scrollTop + 1) * cols,
                    (scrollBottom - scrollTop) * cols);
            System.arraycopy(styles, scrollTop * cols, styles, (scrollTop + 1) * cols,
                    (scrollBottom - scrollTop) * cols);
            for (int col = 0; col < cols; col++) {
                chars[scrollTop * cols + col] = 0;
                styles[scrollTop * cols + col] = DEFAULT_STYLE;
            }
        }
    }

    private void pushScrollback() {
        int[] c = new int[cols];
        long[] s = new long[cols];
        System.arraycopy(chars, 0, c, 0, cols);
        System.arraycopy(styles, 0, s, 0, cols);
        scrollback.addLast(new ScreenLine(c, s));
        while (scrollback.size() > scrollbackMax) {
            scrollback.removeFirst();
        }
    }

    private void scrollUp(int n) {
        scrollRegionUp(n);
    }

    private void scrollDown(int n) {
        scrollRegionDown(n);
    }

    private void cursorUp(int n) {
        cursorRow = Math.max(scrollTop, cursorRow - n);
        pendingWrap = false;
    }

    private void cursorDown(int n) {
        cursorRow = Math.min(scrollBottom, cursorRow + n);
        pendingWrap = false;
    }

    private void cursorForward(int n) {
        cursorCol = Math.min(cols - 1, cursorCol + n);
        pendingWrap = false;
    }

    private void cursorBackward(int n) {
        cursorCol = Math.max(0, cursorCol - n);
        pendingWrap = false;
    }

    private void eraseDisplay(int mode) {
        switch (mode) {
            case 0:
                eraseInRow(cursorRow, cursorCol, cols - 1);
                for (int r = cursorRow + 1; r < rows; r++) eraseInRow(r, 0, cols - 1);
                break;
            case 1:
                eraseInRow(cursorRow, 0, cursorCol);
                for (int r = 0; r < cursorRow; r++) eraseInRow(r, 0, cols - 1);
                break;
            case 2:
                for (int r = 0; r < rows; r++) eraseInRow(r, 0, cols - 1);
                break;
            case 3:
                scrollback.clear();
                for (int r = 0; r < rows; r++) eraseInRow(r, 0, cols - 1);
                break;
            default:
                break;
        }
        pendingWrap = false;
    }

    private void eraseLine(int mode) {
        switch (mode) {
            case 0: eraseInRow(cursorRow, cursorCol, cols - 1); break;
            case 1: eraseInRow(cursorRow, 0, cursorCol); break;
            case 2: eraseInRow(cursorRow, 0, cols - 1); break;
            default: break;
        }
        pendingWrap = false;
    }

    private void eraseInRow(int row, int start, int end) {
        if (row < 0 || row >= rows) return;
        for (int col = Math.max(0, start); col <= Math.min(cols - 1, end); col++) {
            chars[row * cols + col] = 0;
            styles[row * cols + col] = DEFAULT_STYLE;
        }
    }

    private void eraseChars(int n) {
        for (int i = 0; i < n && cursorCol + i < cols; i++) {
            chars[cursorRow * cols + cursorCol + i] = 0;
            styles[cursorRow * cols + cursorCol + i] = DEFAULT_STYLE;
        }
    }

    private void insertLines(int n) {
        for (int i = 0; i < n; i++) {
            System.arraycopy(chars, cursorRow * cols, chars, (cursorRow + 1) * cols,
                    (scrollBottom - cursorRow) * cols);
            System.arraycopy(styles, cursorRow * cols, styles, (cursorRow + 1) * cols,
                    (scrollBottom - cursorRow) * cols);
            for (int col = 0; col < cols; col++) {
                chars[cursorRow * cols + col] = 0;
                styles[cursorRow * cols + col] = DEFAULT_STYLE;
            }
        }
    }

    private void deleteLines(int n) {
        for (int i = 0; i < n; i++) {
            System.arraycopy(chars, (cursorRow + 1) * cols, chars, cursorRow * cols,
                    (scrollBottom - cursorRow) * cols);
            System.arraycopy(styles, (cursorRow + 1) * cols, styles, cursorRow * cols,
                    (scrollBottom - cursorRow) * cols);
            for (int col = 0; col < cols; col++) {
                chars[scrollBottom * cols + col] = 0;
                styles[scrollBottom * cols + col] = DEFAULT_STYLE;
            }
        }
    }

    private void insertChars(int n) {
        for (int i = 0; i < n; i++) {
            for (int col = cols - 1; col > cursorCol; col--) {
                chars[cursorRow * cols + col] = chars[cursorRow * cols + col - 1];
                styles[cursorRow * cols + col] = styles[cursorRow * cols + col - 1];
            }
            chars[cursorRow * cols + cursorCol] = 0;
            styles[cursorRow * cols + cursorCol] = DEFAULT_STYLE;
        }
    }

    private void deleteChars(int n) {
        for (int i = 0; i < n; i++) {
            for (int col = cursorCol; col < cols - 1; col++) {
                chars[cursorRow * cols + col] = chars[cursorRow * cols + col + 1];
                styles[cursorRow * cols + col] = styles[cursorRow * cols + col + 1];
            }
            chars[cursorRow * cols + cols - 1] = 0;
            styles[cursorRow * cols + cols - 1] = DEFAULT_STYLE;
        }
    }

    private void saveCursor() {
        savedRow = cursorRow;
        savedCol = cursorCol;
        savedStyle = currentStyle;
    }

    private void restoreCursor() {
        cursorRow = clamp(savedRow, 0, rows - 1);
        cursorCol = clamp(savedCol, 0, cols - 1);
        currentStyle = savedStyle;
        pendingWrap = false;
    }

    private void enterAltScreen() {
        if (altActive) return;
        altSavedRow = cursorRow;
        altSavedCol = cursorCol;
        altSavedStyle = currentStyle;
        altChars = new ScreenLine[rows];
        for (int r = 0; r < rows; r++) {
            int[] c = new int[cols];
            long[] s = new long[cols];
            System.arraycopy(chars, r * cols, c, 0, cols);
            System.arraycopy(styles, r * cols, s, 0, cols);
            altChars[r] = new ScreenLine(c, s);
        }
        altActive = true;
        for (int r = 0; r < rows; r++) eraseInRow(r, 0, cols - 1);
        cursorRow = 0;
        cursorCol = 0;
    }

    private void leaveAltScreen() {
        if (!altActive) return;
        for (int r = 0; r < rows && altChars != null && r < altChars.length; r++) {
            System.arraycopy(altChars[r].chars, 0, chars, r * cols, Math.min(cols, altChars[r].chars.length));
            System.arraycopy(altChars[r].styles, 0, styles, r * cols, Math.min(cols, altChars[r].styles.length));
        }
        altActive = false;
        cursorRow = clamp(altSavedRow, 0, rows - 1);
        cursorCol = clamp(altSavedCol, 0, cols - 1);
        currentStyle = altSavedStyle;
        altChars = null;
    }

    private void fullReset() {
        currentStyle = DEFAULT_STYLE;
        cursorRow = 0;
        cursorCol = 0;
        cursorVisible = true;
        scrollTop = 0;
        scrollBottom = rows - 1;
        originMode = false;
        insertMode = false;
        autoWrap = true;
        appCursorKeys = false;
        appKeypad = false;
        bracketedPaste = false;
        clearAll();
        scrollback.clear();
    }

    public synchronized String getSelectedText(int startViewRow, int startCol, int endViewRow, int endCol) {
        if (startViewRow > endViewRow || (startViewRow == endViewRow && startCol > endCol)) {
            int tr = startViewRow, tc = startCol;
            startViewRow = endViewRow;
            startCol = endCol;
            endViewRow = tr;
            endCol = tc;
        }
        StringBuilder sb = new StringBuilder();
        for (int viewRow = startViewRow; viewRow <= endViewRow; viewRow++) {
            ScreenLine line = getLine(viewRow);
            if (line == null) continue;
            int from = (viewRow == startViewRow) ? Math.max(0, startCol) : 0;
            int to = (viewRow == endViewRow) ? Math.min(cols - 1, endCol) : cols - 1;
            StringBuilder row = new StringBuilder();
            for (int col = from; col <= to; col++) {
                int ch = line.chars[Math.min(col, line.chars.length - 1)];
                if (ch == 0x200B) continue;
                row.append(ch == 0 ? ' ' : (char) ch);
            }
            int len = row.length();
            while (len > 0 && row.charAt(len - 1) == ' ') {
                row.setLength(--len);
            }
            sb.append(row);
            if (viewRow != endViewRow) sb.append('\n');
        }
        return sb.toString();
    }

    public static int charWidth(int codePoint) {
        if (codePoint == 0 || codePoint < 32) return 1;
        if (codePoint >= 0x0300 && codePoint <= 0x036F) return 0;
        if (codePoint >= 0x1AB0 && codePoint <= 0x1AFF) return 0;
        if (codePoint >= 0x1DC0 && codePoint <= 0x1DFF) return 0;
        if (codePoint >= 0x20D0 && codePoint <= 0x20FF) return 0;
        if (codePoint >= 0xFE20 && codePoint <= 0xFE2F) return 0;
        if (codePoint >= 0x1100 && codePoint <= 0x115F) return 2;
        if (codePoint >= 0x2E80 && codePoint <= 0xA4CF) return 2;
        if (codePoint >= 0xA960 && codePoint <= 0xA97F) return 2;
        if (codePoint >= 0xAC00 && codePoint <= 0xD7A3) return 2;
        if (codePoint >= 0xF900 && codePoint <= 0xFAFF) return 2;
        if (codePoint >= 0xFE10 && codePoint <= 0xFE19) return 2;
        if (codePoint >= 0xFE30 && codePoint <= 0xFE6F) return 2;
        if (codePoint >= 0xFF00 && codePoint <= 0xFF60) return 2;
        if (codePoint >= 0xFFE0 && codePoint <= 0xFFE6) return 2;
        if (codePoint >= 0x1F300 && codePoint <= 0x1FAFF) return 2;
        if (codePoint >= 0x20000 && codePoint <= 0x3FFFD) return 2;
        return 1;
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    /** Build the byte sequence a key press should send, honouring DEC modes. */
    public byte[] keyToBytes(int keyCode, int metaState) {
        boolean ctrl = (metaState & android.view.KeyEvent.META_CTRL_ON) != 0;
        boolean alt = (metaState & android.view.KeyEvent.META_ALT_ON) != 0;
        String seq = null;
        switch (keyCode) {
            case android.view.KeyEvent.KEYCODE_DPAD_UP: seq = appCursorKeys ? "\u001BOA" : "\u001B[A"; break;
            case android.view.KeyEvent.KEYCODE_DPAD_DOWN: seq = appCursorKeys ? "\u001BOB" : "\u001B[B"; break;
            case android.view.KeyEvent.KEYCODE_DPAD_RIGHT: seq = appCursorKeys ? "\u001BOC" : "\u001B[C"; break;
            case android.view.KeyEvent.KEYCODE_DPAD_LEFT: seq = appCursorKeys ? "\u001BOD" : "\u001B[D"; break;
            case android.view.KeyEvent.KEYCODE_MOVE_HOME: seq = "\u001B[H"; break;
            case android.view.KeyEvent.KEYCODE_MOVE_END: seq = "\u001B[F"; break;
            case android.view.KeyEvent.KEYCODE_PAGE_UP: seq = "\u001B[5~"; break;
            case android.view.KeyEvent.KEYCODE_PAGE_DOWN: seq = "\u001B[6~"; break;
            case android.view.KeyEvent.KEYCODE_DEL: seq = "\u007F"; break;
            case android.view.KeyEvent.KEYCODE_FORWARD_DEL: seq = "\u001B[3~"; break;
            case android.view.KeyEvent.KEYCODE_INSERT: seq = "\u001B[2~"; break;
            case android.view.KeyEvent.KEYCODE_ENTER: seq = "\r"; break;
            case android.view.KeyEvent.KEYCODE_TAB: seq = "\t"; break;
            case android.view.KeyEvent.KEYCODE_ESCAPE: seq = "\u001B"; break;
            case android.view.KeyEvent.KEYCODE_F1: seq = "\u001BOP"; break;
            case android.view.KeyEvent.KEYCODE_F2: seq = "\u001BOQ"; break;
            case android.view.KeyEvent.KEYCODE_F3: seq = "\u001BOR"; break;
            case android.view.KeyEvent.KEYCODE_F4: seq = "\u001BOS"; break;
            case android.view.KeyEvent.KEYCODE_F5: seq = "\u001B[15~"; break;
            case android.view.KeyEvent.KEYCODE_F6: seq = "\u001B[17~"; break;
            case android.view.KeyEvent.KEYCODE_F7: seq = "\u001B[18~"; break;
            case android.view.KeyEvent.KEYCODE_F8: seq = "\u001B[19~"; break;
            case android.view.KeyEvent.KEYCODE_F9: seq = "\u001B[20~"; break;
            case android.view.KeyEvent.KEYCODE_F10: seq = "\u001B[21~"; break;
            case android.view.KeyEvent.KEYCODE_F11: seq = "\u001B[23~"; break;
            case android.view.KeyEvent.KEYCODE_F12: seq = "\u001B[24~"; break;
            default: return null;
        }
        byte[] base = seq.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (alt) {
            byte[] withAlt = new byte[base.length + 1];
            withAlt[0] = 0x1B;
            System.arraycopy(base, 0, withAlt, 1, base.length);
            return withAlt;
        }
        return base;
    }

    public List<String> scrollbackLines() {
        List<String> lines = new ArrayList<>();
        for (ScreenLine line : scrollback) {
            StringBuilder sb = new StringBuilder();
            int last = line.chars.length - 1;
            while (last >= 0 && line.chars[last] == 0) last--;
            for (int i = 0; i <= last; i++) {
                int ch = line.chars[i];
                if (ch == 0x200B) continue;
                sb.append(ch == 0 ? ' ' : (char) ch);
            }
            lines.add(sb.toString());
        }
        return lines;
    }
}
