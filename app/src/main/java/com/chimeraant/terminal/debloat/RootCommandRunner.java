package com.chimeraant.terminal.debloat;

import com.chimeraant.terminal.root.RootManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/** Executes commands through a real device {@code su} (uid 0). */
public class RootCommandRunner implements CommandRunner {

    private final RootManager rootManager;

    public RootCommandRunner(RootManager rootManager) {
        this.rootManager = rootManager;
    }

    @Override
    public String name() {
        return "Root (su)";
    }

    @Override
    public Privilege privilege() {
        return Privilege.ROOT;
    }

    @Override
    public boolean isAvailable() {
        return rootManager.getSuPath() != null;
    }

    @Override
    public String unavailableReason() {
        return "No su binary found. This device is not rooted.";
    }

    @Override
    public Result run(String... argv) {
        List<String> command = new ArrayList<>();
        command.add(rootManager.getSuPath());
        command.add("-c");
        command.add(join(argv));
        return exec(command);
    }

    @Override
    public Result run(List<String> argv) {
        return run(argv.toArray(new String[0]));
    }

    private static String join(String[] argv) {
        StringBuilder sb = new StringBuilder();
        for (String arg : argv) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(quote(arg));
        }
        return sb.toString();
    }

    private static String quote(String value) {
        if (value.matches("[A-Za-z0-9_./=:@%+-]+")) return value;
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private Result exec(List<String> command) {
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
            return new Result(-1, "", "Cannot run su: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, "", "Interrupted");
        } finally {
            if (process != null) process.destroy();
        }
    }

    private static void drain(java.io.InputStream stream, StringBuilder sink) {
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
