package com.chimeraant.terminal.view;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.inputmethodservice.InputMethodService;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * A terminal-first soft keyboard with the keys a shell actually needs
 * (Esc, Tab, Ctrl, Alt, arrows, function keys). It is an ordinary
 * {@link InputMethodService}, so users are free to switch to any other
 * installed keyboard - this one is optional, never forced.
 */
public class ChimeraKeyboard extends InputMethodService {

    private static final int COLOR_BG = 0xFF1B1B22;
    private static final int COLOR_KEY = 0xFF2C2C36;
    private static final int COLOR_KEY_ACTIVE = 0xFFA8C88A;
    private static final int COLOR_KEY_SPECIAL = 0xFF3A3A46;
    private static final int COLOR_TEXT = 0xFFEDEDF2;
    private static final int COLOR_TEXT_ON_ACTIVE = 0xFF14141A;

    private LinearLayout root;
    private boolean ctrlLatched;
    private boolean altLatched;
    private boolean shiftLatched;
    private boolean symbolsMode;

    private final List<TextView> ctrlKeys = new ArrayList<>();
    private final List<TextView> altKeys = new ArrayList<>();
    private final List<TextView> shiftKeys = new ArrayList<>();
    private final List<TextView> letterKeys = new ArrayList<>();
    private final List<Character> letterChars = new ArrayList<>();

    @Override
    public View onCreateInputView() {
        buildKeyboard();
        return root;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        if (info != null && (info.inputType & InputType.TYPE_MASK_CLASS) != 0) {
            // Nothing special; keyboard is always visible for the terminal.
        }
    }

    public void setKeyboardColors(int background, int key, int accent) {
        // Hook for future theming; kept simple for now.
    }

    private void buildKeyboard() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);
        int pad = dp(3);
        root.setPadding(pad, pad, pad, pad);

        // Function/navigation row.
        LinearLayout fnRow = row();
        addKey(fnRow, "ESC", 1f, KeyType.SPECIAL, "ESC", null);
        addKey(fnRow, "TAB", 1f, KeyType.SPECIAL, "TAB", null);
        addModifier(fnRow, "CTRL", 1.2f, Modifier.CTRL);
        addModifier(fnRow, "ALT", 1f, Modifier.ALT);
        addKey(fnRow, "/", 1f, KeyType.TEXT, "/", null);
        addKey(fnRow, "-", 1f, KeyType.TEXT, "-", null);
        addKey(fnRow, "\u2191", 1f, KeyType.SPECIAL, "UP", null);
        root.addView(fnRow);

        buildLetterRows();

        // Bottom utility row.
        LinearLayout bottom = row();
        addKey(bottom, symbolsMode ? "ABC" : "?123", 1.4f, KeyType.MODE, null, null);
        addKey(bottom, "\u2190", 1f, KeyType.SPECIAL, "LEFT", null);
        addKey(bottom, "\u2193", 1f, KeyType.SPECIAL, "DOWN", null);
        addKey(bottom, "\u2192", 1f, KeyType.SPECIAL, "RIGHT", null);
        addKey(bottom, "ENTER", 2f, KeyType.SPECIAL, "ENTER", null);
        addKey(bottom, "BKSP", 1.4f, KeyType.SPECIAL, "BACKSPACE", null);
        root.addView(bottom);
    }

    private void buildLetterRows() {
        if (symbolsMode) {
            LinearLayout r1 = row();
            String[] sym1 = {"!", "@", "#", "$", "%", "^", "&", "*", "(", ")"};
            for (String s : sym1) addKey(r1, s, 1f, KeyType.TEXT, s, null);
            root.addView(r1);

            LinearLayout r2 = row();
            String[] sym2 = {"-", "_", "=", "+", "[", "]", "{", "}", "\\", "|"};
            for (String s : sym2) addKey(r2, s, 1f, KeyType.TEXT, s, null);
            root.addView(r2);

            LinearLayout r3 = row();
            String[] sym3 = {";", ":", "'", "\"", ",", ".", "<", ">", "?", "/"};
            for (String s : sym3) addKey(r3, s, 1f, KeyType.TEXT, s, null);
            root.addView(r3);

            LinearLayout r4 = row();
            addModifier(r4, "SHIFT", 1.4f, Modifier.SHIFT);
            String[] sym4 = {"~", "`", "€", "£", "•", "→", "~", "⎋", "^", "%"};
            for (String s : sym4) addKey(r4, s, 1f, KeyType.TEXT, s, null);
            addKey(r4, "\u232B", 1.6f, KeyType.SPECIAL, "BACKSPACE", null);
            root.addView(r4);
            return;
        }

        ctrlKeys.clear();
        altKeys.clear();
        shiftKeys.clear();
        letterKeys.clear();
        letterChars.clear();

        LinearLayout r1 = row();
        for (char c : "qwertyuiop".toCharArray()) addLetter(r1, c);
        root.addView(r1);

        LinearLayout r2 = row();
        for (char c : "asdfghjkl".toCharArray()) addLetter(r2, c);
        root.addView(r2);

        LinearLayout r3 = row();
        addModifier(r3, "SHIFT", 1.4f, Modifier.SHIFT);
        for (char c : "zxcvbnm".toCharArray()) addLetter(r3, c);
        addKey(r3, "\u232B", 1.6f, KeyType.SPECIAL, "BACKSPACE", null);
        root.addView(r3);
    }

    private void addLetter(LinearLayout parent, char lower) {
        String label = shiftLatched ? String.valueOf(Character.toUpperCase(lower)) : String.valueOf(lower);
        TextView key = createKey(label, 1f, KeyType.TEXT);
        key.setTag(String.valueOf(lower));
        key.setOnClickListener(v -> {
            char c = lower;
            if (shiftLatched) {
                c = Character.toUpperCase(lower);
            }
            sendChar(c);
            clearLatches();
        });
        letterKeys.add(key);
        letterChars.add(lower);
        parent.addView(key);
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        row.setLayoutParams(lp);
        return row;
    }

    private enum KeyType {TEXT, SPECIAL, MODE}

    private enum Modifier {CTRL, ALT, SHIFT}

    private void addKey(LinearLayout parent, String label, float weight, KeyType type,
                        String payload, Modifier modifier) {
        TextView key = createKey(label, weight, type);
        key.setOnClickListener(v -> {
            if (type == KeyType.MODE) {
                symbolsMode = !symbolsMode;
                buildKeyboard();
                setInputView(root);
                return;
            }
            if (type == KeyType.SPECIAL) {
                sendSpecial(payload);
            } else if (type == KeyType.TEXT) {
                if (ctrlLatched && payload.length() == 1) {
                    sendCtrl(payload.charAt(0));
                } else if (altLatched) {
                    sendAlt(payload);
                } else {
                    sendText(payload);
                }
                clearLatches();
            }
        });
        parent.addView(key);
    }

    private void addModifier(LinearLayout parent, String label, float weight, Modifier modifier) {
        TextView key = createKey(label, weight, KeyType.SPECIAL);
        key.setOnClickListener(v -> {
            switch (modifier) {
                case CTRL:
                    ctrlLatched = !ctrlLatched;
                    altLatched = false;
                    break;
                case ALT:
                    altLatched = !altLatched;
                    ctrlLatched = false;
                    break;
                case SHIFT:
                    shiftLatched = !shiftLatched;
                    refreshLetterLabels();
                    break;
            }
            refreshModifierState();
        });
        if (modifier == Modifier.CTRL) ctrlKeys.add(key);
        if (modifier == Modifier.ALT) altKeys.add(key);
        if (modifier == Modifier.SHIFT) shiftKeys.add(key);
        parent.addView(key);
    }

    private TextView createKey(String label, float weight, KeyType type) {
        TextView key = new TextView(this);
        key.setText(label);
        key.setTextColor(COLOR_TEXT);
        key.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        key.setGravity(Gravity.CENTER);
        key.setPadding(0, dp(12), 0, dp(12));
        key.setTypeface(android.graphics.Typeface.MONOSPACE);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        bg.setColor(type == KeyType.SPECIAL ? COLOR_KEY_SPECIAL : COLOR_KEY);
        bg.setStroke(dp(1), 0x33FFFFFF);
        key.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        key.setLayoutParams(lp);
        return key;
    }

    private void refreshModifierState() {
        highlight(ctrlKeys, ctrlLatched);
        highlight(altKeys, altLatched);
        highlight(shiftKeys, shiftLatched);
    }

    private void refreshLetterLabels() {
        for (int i = 0; i < letterKeys.size(); i++) {
            char c = letterChars.get(i);
            letterKeys.get(i).setText(shiftLatched
                    ? String.valueOf(Character.toUpperCase(c))
                    : String.valueOf(c));
        }
    }

    private void highlight(List<TextView> keys, boolean active) {
        for (TextView key : keys) {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(8));
            bg.setColor(active ? COLOR_KEY_ACTIVE : COLOR_KEY_SPECIAL);
            bg.setStroke(dp(1), 0x33FFFFFF);
            key.setBackground(bg);
            key.setTextColor(active ? COLOR_TEXT_ON_ACTIVE : COLOR_TEXT);
        }
    }

    private void clearLatches() {
        ctrlLatched = false;
        altLatched = false;
        shiftLatched = false;
        refreshModifierState();
        refreshLetterLabels();
    }

    private void sendText(String text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.commitText(text, 1);
    }

    private void sendChar(char c) {
        if (ctrlLatched) {
            sendCtrl(c);
            return;
        }
        String value = String.valueOf(c);
        if (altLatched) {
            sendAlt(value);
            return;
        }
        sendText(value);
    }

    private void sendCtrl(char c) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.commitText(TerminalInputConnection.TOKEN_CTRL_PREFIX + c
                    + TerminalInputConnection.TOKEN_END, 1);
        }
    }

    private void sendAlt(String value) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.commitText(TerminalInputConnection.TOKEN_ALT_PREFIX + value
                    + TerminalInputConnection.TOKEN_END, 1);
        }
    }

    private void sendSpecial(String name) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.commitText(TerminalInputConnection.TOKEN_SPECIAL_PREFIX + name
                    + TerminalInputConnection.TOKEN_END, 1);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
