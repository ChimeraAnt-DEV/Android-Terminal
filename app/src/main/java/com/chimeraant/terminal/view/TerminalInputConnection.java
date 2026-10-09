package com.chimeraant.terminal.view;

import android.os.Build;
import android.view.KeyEvent;
import android.view.inputmethod.BaseInputConnection;

import java.nio.charset.StandardCharsets;

/**
 * Bridges the Android IME (including our own {@code ChimeraKeyboard}) into the
 * terminal. Ordinary text is forwarded verbatim; special keys are translated to
 * the escape sequences expected by the running program.
 */
public class TerminalInputConnection extends BaseInputConnection {

    private final TerminalView terminalView;

    // Sentinel characters emitted by ChimeraKeyboard for modifier chords.
    public static final String TOKEN_CTRL_PREFIX = "\u0000CTRL:";
    public static final String TOKEN_ALT_PREFIX = "\u0000ALT:";
    public static final String TOKEN_SPECIAL_PREFIX = "\u0000KEY:";
    public static final String TOKEN_END = "\u0000";

    public TerminalInputConnection(TerminalView view, boolean fullEditor) {
        super(view, fullEditor);
        this.terminalView = view;
    }

    @Override
    public boolean commitText(CharSequence text, int newCursorPosition) {
        if (text == null) return true;
        String value = text.toString();
        if (value.isEmpty()) return true;

        if (value.startsWith(TOKEN_CTRL_PREFIX)) {
            handleCtrlToken(value);
            return true;
        }
        if (value.startsWith(TOKEN_ALT_PREFIX)) {
            handleAltToken(value);
            return true;
        }
        if (value.startsWith(TOKEN_SPECIAL_PREFIX)) {
            handleSpecialToken(value);
            return true;
        }

        terminalView.sendText(value);
        return true;
    }

    private void handleCtrlToken(String value) {
        String body = stripToken(value, TOKEN_CTRL_PREFIX);
        if (body.isEmpty()) return;
        char c = body.charAt(0);
        int control = controlCode(c);
        if (control >= 0) {
            terminalView.sendText(String.valueOf((char) control));
        }
    }

    private void handleAltToken(String value) {
        String body = stripToken(value, TOKEN_ALT_PREFIX);
        if (body.isEmpty()) return;
        terminalView.sendText("\u001B" + body);
    }

    private void handleSpecialToken(String value) {
        String name = stripToken(value, TOKEN_SPECIAL_PREFIX);
        String seq;
        switch (name) {
            case "UP": seq = "\u001B[A"; break;
            case "DOWN": seq = "\u001B[B"; break;
            case "RIGHT": seq = "\u001B[C"; break;
            case "LEFT": seq = "\u001B[D"; break;
            case "HOME": seq = "\u001B[H"; break;
            case "END": seq = "\u001B[F"; break;
            case "PGUP": seq = "\u001B[5~"; break;
            case "PGDN": seq = "\u001B[6~"; break;
            case "DEL": seq = "\u001B[3~"; break;
            case "INS": seq = "\u001B[2~"; break;
            case "ESC": seq = "\u001B"; break;
            case "TAB": seq = "\t"; break;
            case "ENTER": seq = "\r"; break;
            case "BACKSPACE": seq = "\u007F"; break;
            case "F1": seq = "\u001BOP"; break;
            case "F2": seq = "\u001BOQ"; break;
            case "F3": seq = "\u001BOR"; break;
            case "F4": seq = "\u001BOS"; break;
            case "F5": seq = "\u001B[15~"; break;
            case "F6": seq = "\u001B[17~"; break;
            case "F7": seq = "\u001B[18~"; break;
            case "F8": seq = "\u001B[19~"; break;
            case "F9": seq = "\u001B[20~"; break;
            case "F10": seq = "\u001B[21~"; break;
            case "F11": seq = "\u001B[23~"; break;
            case "F12": seq = "\u001B[24~"; break;
            default: return;
        }
        terminalView.sendText(seq);
    }

    private static String stripToken(String value, String prefix) {
        int start = prefix.length();
        int end = value.indexOf(TOKEN_END, start);
        if (end < 0) end = value.length();
        return value.substring(start, end);
    }

    private static int controlCode(char c) {
        if (c >= 'a' && c <= 'z') return c - 'a' + 1;
        if (c >= 'A' && c <= 'Z') return c - 'A' + 1;
        switch (c) {
            case ' ': return 0;
            case '@': return 0;
            case '[': return 27;
            case '\\': return 28;
            case ']': return 29;
            case '^': return 30;
            case '_': return 31;
            case '?': return 127;
            default: return -1;
        }
    }

    @Override
    public boolean deleteSurroundingText(int beforeLength, int afterLength) {
        for (int i = 0; i < beforeLength; i++) {
            terminalView.sendText("\u007F");
        }
        return true;
    }

    @Override
    public boolean sendKeyEvent(KeyEvent event) {
        terminalView.sendKey(event);
        return true;
    }

    @Override
    public boolean performEditorAction(int actionCode) {
        terminalView.sendText("\r");
        return true;
    }

    @Override
    public boolean setComposingText(CharSequence text, int newCursorPosition) {
        return commitText(text, newCursorPosition);
    }

    @Override
    public boolean finishComposingText() {
        return true;
    }

    @Override
    public CharSequence getTextBeforeCursor(int length, int flags) {
        return "";
    }

    @Override
    public CharSequence getTextAfterCursor(int length, int flags) {
        return "";
    }
}
