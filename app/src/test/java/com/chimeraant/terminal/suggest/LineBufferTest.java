package com.chimeraant.terminal.suggest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/** The dropdown only helps if the mirrored input line is accurate. */
public class LineBufferTest {

    private static void type(LineBuffer buffer, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        buffer.accept(bytes);
    }

    @Test
    public void tracksSimpleTyping() {
        LineBuffer buffer = new LineBuffer();
        type(buffer, "hel");
        assertEquals("hel", buffer.currentLine());
        assertEquals("hel", buffer.currentWord());
        assertTrue(buffer.atCommandStart());
    }

    @Test
    public void backspaceRemovesLastCharacter() {
        LineBuffer buffer = new LineBuffer();
        type(buffer, "hellp");
        type(buffer, "\u007F");
        assertEquals("hell", buffer.currentLine());
    }

    @Test
    public void enterClearsTheLine() {
        LineBuffer buffer = new LineBuffer();
        type(buffer, "ls -la");
        type(buffer, "\r");
        assertEquals("", buffer.currentLine());
    }

    @Test
    public void arrowKeysAreNotTreatedAsText() {
        LineBuffer buffer = new LineBuffer();
        type(buffer, "ls");
        type(buffer, "\u001B[A"); // up arrow
        assertEquals("ls", buffer.currentLine());
    }

    @Test
    public void secondWordIsNotACommandStart() {
        LineBuffer buffer = new LineBuffer();
        type(buffer, "cat fi");
        assertEquals("fi", buffer.currentWord());
        assertFalse(buffer.atCommandStart());
    }

    @Test
    public void ctrlCClearsTheLine() {
        LineBuffer buffer = new LineBuffer();
        type(buffer, "rm -rf /");
        type(buffer, "\u0003");
        assertEquals("", buffer.currentLine());
    }

    @Test
    public void suggestionsMatchByPrefix() {
        java.util.List<CommandDatabase.Command> matches =
                CommandDatabase.startingWith("he", 5);
        assertFalse(matches.isEmpty());
        // Sorted alphabetically: head comes before help.
        assertEquals("head", matches.get(0).name);
        assertEquals("help", matches.get(1).name);
    }

    @Test
    public void everySuggestionHasAnExplanation() {
        for (CommandDatabase.Command command : CommandDatabase.all()) {
            assertFalse("missing summary for " + command.name,
                    command.summary == null || command.summary.trim().isEmpty());
        }
    }

    @Test
    public void helpIsInTheCatalogue() {
        assertTrue(CommandDatabase.find("help") != null);
    }
}
