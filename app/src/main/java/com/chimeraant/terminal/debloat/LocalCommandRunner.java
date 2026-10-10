package com.chimeraant.terminal.debloat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs commands as the app itself (no elevation).
 *
 * Useful for diagnostics and for the parts of debloating that genuinely work
 * without privilege, such as listing what is installed. It cannot disable or
 * remove system packages, and it says so rather than failing silently.
 */
public class LocalCommandRunner implements CommandRunner {

    @Override
    public String name() {
        return "App (no elevation)";
    }

    @Override
    public Privilege privilege() {
        return Privilege.NONE;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String unavailableReason() {
        return "";
    }

    @Override
    public Result run(String... argv) {
        List<String> command = new ArrayList<>();
        for (String arg : argv) command.add(arg);
        return exec(command);
    }

    @Override
    public Result run(List<String> argv) {
        return exec(argv);
    }

    private Result exec(List<String> command) {
        if (command.isEmpty()) return new Result(-1, "", "Empty command");
        Process process = null;
        try {
            final Process started = new ProcessBuilder(command)
                    .redirectErrorStream(false).start();
            process = started;
            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            Thread errReader = new Thread(() -> drain(started.getErrorStream(), err));
            errReader.setDaemon(true);
            errReader.start();
            drain(started.getInputStream(), out);
            int code = started.waitFor();
            errReader.join(1000);
            return new Result(code, out.toString(), err.toString());
        } catch (IOException e) {
            return new Result(-1, "", "Cannot run command: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, "", "Interrupted");
        } finally {
            if (process != null) process.destroy();
        }
    }

    private static void drain(InputStream stream, StringBuilder sink) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sink.append(line).append('\n');
            }
        } catch (IOException ignored) {
            // Stream closed; nothing to add.
        }
    }
}
