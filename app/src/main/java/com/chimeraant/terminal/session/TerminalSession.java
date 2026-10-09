package com.chimeraant.terminal.session;

import android.os.Handler;
import android.os.Looper;

import com.chimeraant.terminal.core.PtyProcess;
import com.chimeraant.terminal.core.TerminalEmulator;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A single sandbox: one shell process bound to its own pseudo-terminal and its
 * own working directory. Sandboxes never share file descriptors or threads, so
 * a runaway job in one session cannot stall the others.
 */
public class TerminalSession {

    public interface Listener {
        void onSessionChanged(TerminalSession session);

        void onSessionFinished(TerminalSession session);
    }

    /** Immutable description of how a session should be started. */
    public static class Config {
        public final String name;
        public final String[] command;
        public final String workingDirectory;
        public final java.util.Map<String, String> environment;

        public Config(String name, String[] command, String workingDirectory,
                      java.util.Map<String, String> environment) {
            this.name = name;
            this.command = command;
            this.workingDirectory = workingDirectory;
            this.environment = environment;
        }
    }

    private static final int READ_BUFFER = 8192;

    private final Config config;
    private final Listener listener;
    private final TerminalEmulator emulator;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private PtyProcess process;
    private Thread readerThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile int exitCode = -1;
    private volatile boolean finished = false;

    private final List<String> transcript = new ArrayList<>();

    public TerminalSession(Config config, int initialCols, int initialRows, Listener listener) {
        this.config = config;
        this.listener = listener;
        this.emulator = new TerminalEmulator(initialCols, initialRows, new TerminalEmulator.Listener() {
            @Override
            public void onTitleChanged(String title) {
                postChanged();
            }

            @Override
            public void onBell() {
                // Could trigger a vibration; intentionally quiet.
            }

            @Override
            public void onClipboardRequest(String selection) {
                // OSC 52 handled at the app layer if desired.
            }

            @Override
            public void onScreenChanged() {
                postChanged();
            }

            @Override
            public void onReply(String data) {
                write(data.getBytes(StandardCharsets.UTF_8));
            }
        });
        start();
    }

    public Config getConfig() {
        return config;
    }

    public String getName() {
        return config.name;
    }

    public TerminalEmulator getEmulator() {
        return emulator;
    }

    public boolean isFinished() {
        return finished;
    }

    public int getExitCode() {
        return exitCode;
    }

    public int getPid() {
        return process == null ? -1 : process.getPid();
    }

    private void start() {
        try {
            process = PtyProcess.spawn(config.command,
                    envArray(), config.workingDirectory, emulator.getRows(), emulator.getCols());
        } catch (IOException e) {
            appendError("Failed to start session: " + e.getMessage());
            finished = true;
            return;
        }
        running.set(true);
        readerThread = new Thread(this::readLoop, "pty-reader-" + config.name);
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private String[] envArray() {
        String[] env = new String[config.environment.size()];
        int i = 0;
        for (java.util.Map.Entry<String, String> e : config.environment.entrySet()) {
            env[i++] = e.getKey() + "=" + e.getValue();
        }
        return env;
    }

    private void readLoop() {
        InputStream in = process.getInputStream();
        byte[] buffer = new byte[READ_BUFFER];
        try {
            while (running.get()) {
                int n = in.read(buffer, 0, buffer.length);
                if (n < 0) break;
                if (n == 0) continue;
                emulator.process(buffer, n);
                postChanged();
            }
        } catch (IOException ignored) {
            // PTY closed.
        }
        onReaderExit();
    }

    private void onReaderExit() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        int code = process == null ? -1 : process.waitFor();
        exitCode = code;
        finished = true;
        appendDisconnectedNotice();
        mainHandler.post(() -> {
            if (listener != null) listener.onSessionFinished(TerminalSession.this);
        });
    }

    public void write(byte[] data) {
        if (process == null || process.isClosed()) return;
        try {
            OutputStream out = process.getOutputStream();
            out.write(data);
            out.flush();
        } catch (IOException ignored) {
            // Session already gone.
        }
    }

    public void writeString(String data) {
        write(data.getBytes(StandardCharsets.UTF_8));
    }

    public void resize(int cols, int rows) {
        emulator.resize(cols, rows);
        if (process != null) {
            process.setWindowSize(rows, cols);
        }
    }

    public void sendSignal(int signal) {
        if (process != null) process.sendSignal(signal);
    }

    public int getForegroundPid() {
        return process == null ? -1 : process.getForegroundPid();
    }

    public void close() {
        running.set(false);
        if (process != null) {
            process.close();
        }
    }

    /** Kill and remove this sandbox, freeing its PTY and threads. */
    public void destroy() {
        close();
        finished = true;
    }

    private void appendError(String message) {
        emulator.process(("\r\n\u001B[31m" + message + "\u001B[0m\r\n")
                .getBytes(StandardCharsets.UTF_8), message.length() + 20);
    }

    private void appendDisconnectedNotice() {
        String notice = "\r\n\u001B[90m[process exited with code " + exitCode + "]\u001B[0m\r\n";
        emulator.process(notice.getBytes(StandardCharsets.UTF_8), notice.length());
        postChanged();
    }

    private void postChanged() {
        mainHandler.post(() -> {
            if (listener != null) listener.onSessionChanged(TerminalSession.this);
        });
    }
}
