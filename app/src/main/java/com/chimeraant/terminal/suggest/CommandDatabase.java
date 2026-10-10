package com.chimeraant.terminal.suggest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The catalogue behind the suggestion dropdown.
 *
 * Each entry has the command name plus a one-line explanation written in plain
 * language, so a new user can see what something does before running it.
 */
public final class CommandDatabase {

    /** One suggestion: the command, and what it does in plain English. */
    public static final class Command {
        public final String name;
        public final String summary;
        public final String group;

        public Command(String name, String summary, String group) {
            this.name = name;
            this.summary = summary;
            this.group = group;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static final List<Command> COMMANDS = new ArrayList<>(Arrays.asList(
            // Chimera built-ins
            new Command("help", "List the commands this sandbox can run.", "Basics"),
            new Command("exit", "Close this sandbox and end its shell.", "Basics"),
            new Command("clear", "Erase the screen and move the cursor to the top.", "Basics"),
            new Command("cd", "Change which folder you are working in.", "Files"),
            new Command("pwd", "Show the full path of the folder you are in.", "Files"),
            new Command("ls", "List the files and folders in a directory.", "Files"),
            new Command("cat", "Print the contents of a file.", "Files"),
            new Command("echo", "Print the text you give it.", "Basics"),
            new Command("mkdir", "Create a new folder.", "Files"),
            new Command("rm", "Delete a file or folder. This cannot be undone.", "Files"),
            new Command("cp", "Copy a file or folder to another place.", "Files"),
            new Command("mv", "Move or rename a file or folder.", "Files"),
            new Command("touch", "Create an empty file, or update its timestamp.", "Files"),
            new Command("chmod", "Change who can read, write or run a file.", "Files"),
            new Command("grep", "Search inside files for lines that match a pattern.", "Text"),
            new Command("head", "Show the first lines of a file.", "Text"),
            new Command("tail", "Show the last lines of a file.", "Text"),
            new Command("wc", "Count lines, words and characters in a file.", "Text"),
            new Command("sed", "Find and replace text inside a file.", "Text"),
            new Command("awk", "Process and print columns of text.", "Text"),

            // Android tools
            new Command("getprop", "List Android system properties, such as build version.", "Android"),
            new Command("setprop", "Change an Android system property. Root is often needed.", "Android"),
            new Command("pm", "Manage apps. Try pm list packages to see what is installed.", "Android"),
            new Command("am", "Start apps and activities. Try am start -n package/activity.", "Android"),
            new Command("dumpsys", "Dump detailed system information, such as battery or wifi.", "Android"),
            new Command("settings", "Read or write Android settings values.", "Android"),
            new Command("cmd", "Call a system service. Try cmd wifi status.", "Android"),
            new Command("logcat", "Stream the system log. Useful when debugging apps.", "Android"),
            new Command("input", "Send taps, swipes and key presses to the screen.", "Android"),
            new Command("screencap", "Take a screenshot and save it to a file.", "Android"),
            new Command("screenrecord", "Record the screen to a video file.", "Android"),
            new Command("top", "Show running processes and their CPU use.", "Android"),
            new Command("ps", "List the processes running on the device.", "Android"),
            new Command("getevent", "Show raw touch and key events. Needs root on most builds.", "Android"),
            new Command("svc", "Turn things like wifi, data or power on and off.", "Android"),
            new Command("wm", "Change screen size and density. Try wm size.", "Android"),
            new Command("df", "Show how much free space each storage area has.", "Android"),
            new Command("free", "Show how much memory is in use.", "Android"),
            new Command("uname", "Print kernel and machine details.", "Android"),
            new Command("id", "Show the user and group this shell runs as.", "Android"),
            new Command("su", "Request root. Only works on a rooted device.", "Android"),

            // Termux userland
            new Command("pkg", "Install and update packages. Needs the Termux userland.", "Termux"),
            new Command("apt", "Advanced package tool, as on desktop Linux.", "Termux"),
            new Command("apt-get", "Install, remove and upgrade packages.", "Termux"),
            new Command("bash", "Start the Bash shell instead of the default one.", "Termux"),
            new Command("sh", "Start a simple POSIX shell.", "Termux"),
            new Command("python", "Run Python. Type python alone for an interactive session.", "Termux"),
            new Command("node", "Run JavaScript with Node.js.", "Termux"),
            new Command("git", "Clone, commit and push code repositories.", "Termux"),
            new Command("ssh", "Log in to another computer over the network.", "Termux"),
            new Command("scp", "Copy files to another computer over SSH.", "Termux"),
            new Command("curl", "Download a file or call a web address.", "Network"),
            new Command("wget", "Download a file from the internet.", "Network"),
            new Command("ping", "Check whether a host answers on the network.", "Network"),
            new Command("ifconfig", "Show network interfaces and their addresses.", "Network"),
            new Command("ip", "Show or change network settings.", "Network"),
            new Command("nmap", "Scan a host for open network ports.", "Network"),
            new Command("openssl", "Cryptography tools, certificates and hashes.", "Network"),
            new Command("tar", "Pack or unpack archives.", "Files"),
            new Command("unzip", "Extract files from a zip archive.", "Files"),
            new Command("zip", "Create a zip archive.", "Files"),
            new Command("vi", "A small text editor that runs in the terminal.", "Text"),
            new Command("nano", "An easy text editor that runs in the terminal.", "Text"),
            new Command("less", "Read a long file one screen at a time.", "Text"),
            new Command("man", "Show the manual page for a command.", "Basics")
    ));

    private static final List<Command> SORTED;

    static {
        List<Command> copy = new ArrayList<>(COMMANDS);
        Collections.sort(copy, (a, b) -> a.name.compareToIgnoreCase(b.name));
        SORTED = Collections.unmodifiableList(copy);
    }

    private CommandDatabase() {
    }

    public static List<Command> all() {
        return SORTED;
    }

    /**
     * Commands whose name starts with the given prefix, best match first.
     * An empty prefix returns everything, which is what the dropdown shows
     * before the user has typed anything.
     */
    public static List<Command> startingWith(String prefix, int limit) {
        List<Command> matches = new ArrayList<>();
        String needle = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);

        for (Command command : SORTED) {
            if (command.name.toLowerCase(Locale.ROOT).startsWith(needle)) {
                matches.add(command);
                if (matches.size() >= limit) return matches;
            }
        }
        // Then commands that merely contain the text, for partial recall.
        if (!needle.isEmpty()) {
            for (Command command : SORTED) {
                if (matches.contains(command)) continue;
                if (command.name.toLowerCase(Locale.ROOT).contains(needle)) {
                    matches.add(command);
                    if (matches.size() >= limit) return matches;
                }
            }
        }
        return matches;
    }

    /** Look up a single command by exact name. */
    public static Command find(String name) {
        if (name == null) return null;
        for (Command command : SORTED) {
            if (command.name.equals(name)) return command;
        }
        return null;
    }

    /** The word currently being typed at the end of the input. */
    public static String currentWord(String input) {
        if (input == null || input.isEmpty()) return "";
        int end = input.length();
        int start = end;
        while (start > 0) {
            char c = input.charAt(start - 1);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.') {
                start--;
            } else {
                break;
            }
        }
        return input.substring(start, end);
    }
}
