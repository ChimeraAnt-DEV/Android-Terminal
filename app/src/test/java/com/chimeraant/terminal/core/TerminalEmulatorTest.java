package com.chimeraant.terminal.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * Regression tests for the terminal emulator. These run as plain JVM unit
 * tests (no device or emulator needed).
 */
public class TerminalEmulatorTest {

    private static void feed(TerminalEmulator emulator, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        emulator.process(bytes, bytes.length);
    }

    /** Every stored line must be exactly as wide as the current column count. */
    private static void assertAllLinesMatchWidth(TerminalEmulator emulator) {
        int cols = emulator.getCols();
        int total = emulator.getRows() + emulator.getScrollbackSize();
        for (int row = 0; row < total; row++) {
            TerminalEmulator.ScreenLine line = emulator.getLine(row);
            if (line == null) continue;
            assertEquals("row " + row + " chars width", cols, line.chars.length);
            assertEquals("row " + row + " styles width", cols, line.styles.length);
        }
    }

    @Test
    public void scrollbackSurvivesWidening() {
        TerminalEmulator emulator = new TerminalEmulator(80, 24, null);
        for (int i = 0; i < 100; i++) {
            feed(emulator, "line " + i + "\n");
        }
        assertTrue("expected scrollback", emulator.getScrollbackSize() > 0);

        emulator.resize(120, 40);
        assertAllLinesMatchWidth(emulator);

        // Indexing every cell the way the renderer does must not throw.
        int total = emulator.getRows() + emulator.getScrollbackSize();
        for (int row = 0; row < total; row++) {
            TerminalEmulator.ScreenLine line = emulator.getLine(row);
            assertNotNull(line);
            for (int col = 0; col < emulator.getCols(); col++) {
                line.chars[col] = line.chars[col];
                line.styles[col] = line.styles[col];
            }
        }
    }

    @Test
    public void scrollbackSurvivesNarrowing() {
        TerminalEmulator emulator = new TerminalEmulator(120, 40, null);
        for (int i = 0; i < 100; i++) {
            feed(emulator, "line " + i + "\n");
        }
        emulator.resize(40, 12);
        assertAllLinesMatchWidth(emulator);
    }

    @Test
    public void altScreenSurvivesResize() {
        TerminalEmulator emulator = new TerminalEmulator(80, 24, null);
        feed(emulator, "\u001B[?1049h");
        feed(emulator, "inside alternate screen\n");
        emulator.resize(120, 40);
        feed(emulator, "\u001B[?1049l");
        assertAllLinesMatchWidth(emulator);
    }

    @Test
    public void handlesTruncatedEscapeSequences() {
        TerminalEmulator emulator = new TerminalEmulator(80, 24, null);
        feed(emulator, "\u001B[");
        feed(emulator, "\u001B[38;5;");
        feed(emulator, "\u001B[38;2;1;2");
        feed(emulator, "\u001B]");
        feed(emulator, "\u001B");
        emulator.process(new byte[]{(byte) 0xFF, (byte) 0xFE, (byte) 0x80}, 3);
        assertAllLinesMatchWidth(emulator);
    }

    @Test
    public void handlesTrueColourAndAttributes() {
        TerminalEmulator emulator = new TerminalEmulator(80, 24, null);
        feed(emulator, "\u001B[38;2;168;200;138mX\u001B[0m");
        feed(emulator, "\u001B[48;2;10;20;30mY\u001B[0m");
        feed(emulator, "\u001B[1;3;4;7;9mZ\u001B[0m");
        assertAllLinesMatchWidth(emulator);
    }

    @Test
    public void selectionAndScrollbackText() {
        TerminalEmulator emulator = new TerminalEmulator(80, 24, null);
        for (int i = 0; i < 40; i++) {
            feed(emulator, "row " + i + "\n");
        }
        assertTrue(emulator.scrollbackLines().size() > 0);
        assertNotNull(emulator.getSelectedText(0, 0, emulator.getRows(), 5));
    }
}
