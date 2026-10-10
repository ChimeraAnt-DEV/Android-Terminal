package com.chimeraant.terminal.debloat;

import java.util.List;

/**
 * Runs a command with whatever privilege level a backend can actually provide.
 *
 * Backends never pretend: if a backend cannot elevate, {@link #isAvailable()}
 * is false and {@link #run} reports the real failure instead of silently
 * succeeding.
 */
public interface CommandRunner {

    /** How much privilege this backend actually has. */
    enum Privilege {
        /** No elevation: the app's own uid only. */
        NONE,
        /** Shizuku / adb-equivalent (uid 2000, "shell"). */
        SHELL,
        /** Real root (uid 0). */
        ROOT
    }

    class Result {
        public final int exitCode;
        public final String stdout;
        public final String stderr;

        public Result(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
        }

        public boolean isSuccess() {
            return exitCode == 0;
        }

        public String combined() {
            StringBuilder sb = new StringBuilder();
            if (!stdout.isEmpty()) sb.append(stdout.trim());
            if (!stderr.isEmpty()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(stderr.trim());
            }
            return sb.toString();
        }
    }

    /** Human readable backend name for the UI. */
    String name();

    Privilege privilege();

    boolean isAvailable();

    /** Run argv and wait for completion. */
    Result run(String... argv);

    Result run(List<String> argv);

    /** Explain why this backend is unavailable, for display. */
    String unavailableReason();
}
