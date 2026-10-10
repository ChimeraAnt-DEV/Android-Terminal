package com.chimeraant.terminal.suggest;

import java.nio.charset.StandardCharsets;

/**
 * Tracks the line the user is currently typing.
 *
 * The app knows what it sends to the shell, so it can mirror the shell's input
 * line without reading anything back. That gives the suggestion dropdown the
 * word under the cursor. Escape sequences are ignored because they are not
 * visible text.
 */
public class LineBuffer {

    private static final int MAX_LINE = 4096;

    private final StringBuilder line = new StringBuilder();

    /** Feed bytes that were sent to the shell. */
    public void accept(byte[] data) {
        if (data == null || data.length == 0) return;
        for (int i = 0; i < data.length; i++) {
            int b = data[i] & 0xFF;
            if (b == 0x1B) {
                // Skip an escape sequence: ESC ... until a final byte in
                // the range 0x40 to 0x7E.
                i++;
                if (i < data.length && data[i] == '[') {
                    i++;
                    while (i < data.length) {
                        int c = data[i] & 0xFF;
                        if (c >= 0x40 && c <= 0x7E) break;
                        i++;
                    }
                } else if (i < data.length) {
                    // Two byte sequence, already consumed.
                }
                continue;
            }
            if (b == '\r' || b == '\n') {
                line.setLength(0);
                continue;
            }
            if (b == 0x7F || b == 0x08) {
                if (line.length() > 0) line.setLength(line.length() - 1);
                continue;
            }
            if (b == 0x03 || b == 0x15) {
                // Ctrl+C or Ctrl+U clears the line.
                line.setLength(0);
                continue;
            }
            if (b == '\t') {
                line.append(' ');
                continue;
            }
            if (b < 0x20) continue;
            if (b < 0x80) {
                line.append((char) b);
            } else {
                // Multi-byte UTF-8: decode the run starting here.
                int length = utf8Length(b);
                if (length > 1 && i + length <= data.length) {
                    String decoded = new String(data, i, length, StandardCharsets.UTF_8);
                    line.append(decoded);
                    i += length - 1;
                }
            }
            if (line.length() > MAX_LINE) {
                line.delete(0, line.length() - MAX_LINE);
            }
        }
    }

    private static int utf8Length(int firstByte) {
        if ((firstByte & 0xE0) == 0xC0) return 2;
        if ((firstByte & 0xF0) == 0xE0) return 3;
        if ((firstByte & 0xF8) == 0xF0) return 4;
        return 1;
    }

    /** The whole line typed so far. */
    public String currentLine() {
        return line.toString();
    }

    /** The word under the cursor, which is what suggestions match on. */
    public String currentWord() {
        return CommandDatabase.currentWord(line.toString());
    }

    /** True when the cursor sits at the start of a command (first word). */
    public boolean atCommandStart() {
        String text = line.toString();
        int wordStart = text.length() - currentWord().length();
        return text.substring(0, wordStart).trim().isEmpty();
    }

    public void reset() {
        line.setLength(0);
    }
}
