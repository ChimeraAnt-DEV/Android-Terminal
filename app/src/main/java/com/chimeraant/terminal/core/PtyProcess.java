package com.chimeraant.terminal.core;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

/**
 * A real pseudo-terminal process. Each instance owns an independent PTY master
 * file descriptor and a child process; nothing is shared between instances,
 * which is what keeps multiple sandboxes isolated and cheap.
 */
public class PtyProcess implements Closeable {

    static {
        System.loadLibrary("chimera_pty");
    }

    public static final int SIGINT = 2;
    public static final int SIGQUIT = 3;
    public static final int SIGKILL = 9;
    public static final int SIGTERM = 15;
    public static final int SIGWINCH = 28;

    private final Object writeLock = new Object();
    private int pid = -1;
    private int fd = -1;
    private boolean closed = false;

    private final InputStream inputStream = new InputStream() {
        private final byte[] one = new byte[1];

        @Override
        public int read() throws IOException {
            int n = read(one, 0, 1);
            if (n <= 0) return -1;
            return one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            return PtyProcess.this.read(b, off, len);
        }
    };

    private final OutputStream outputStream = new OutputStream() {
        @Override
        public void write(int b) throws IOException {
            byte[] one = {(byte) b};
            write(one, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            PtyProcess.this.write(b, off, len);
        }
    };

    private PtyProcess(int pid, int fd) {
        this.pid = pid;
        this.fd = fd;
    }

    public static PtyProcess spawn(String[] argv, String[] envp, String cwd, int rows, int cols)
            throws IOException {
        int[] result = nativeCreateSubprocess(argv, envp, cwd, rows, cols);
        if (result == null || result.length < 2 || result[0] < 0 || result[1] < 0) {
            throw new IOException("Unable to allocate pseudo-terminal");
        }
        return new PtyProcess(result[0], result[1]);
    }

    public static PtyProcess spawn(String[] argv, Map<String, String> env, File cwd,
                                   int rows, int cols) throws IOException {
        String[] envp = new String[env.size()];
        int i = 0;
        for (Map.Entry<String, String> e : env.entrySet()) {
            envp[i++] = e.getKey() + "=" + e.getValue();
        }
        return spawn(argv, envp, cwd == null ? null : cwd.getAbsolutePath(), rows, cols);
    }

    public InputStream getInputStream() {
        return inputStream;
    }

    public OutputStream getOutputStream() {
        return outputStream;
    }

    public int getPid() {
        return pid;
    }

    public int getFd() {
        return fd;
    }

    public boolean isClosed() {
        return closed;
    }

    private int read(byte[] b, int off, int len) throws IOException {
        if (closed) return -1;
        int n = nativeRead(fd, b, off, len);
        if (n < 0) return -1;
        return n;
    }

    private void write(byte[] b, int off, int len) throws IOException {
        if (closed) throw new IOException("PTY closed");
        synchronized (writeLock) {
            int written = 0;
            while (written < len) {
                int n = nativeWrite(fd, b, off + written, len - written);
                if (n < 0) {
                    throw new IOException("PTY write failed: " + n);
                }
                written += n;
            }
        }
    }

    public void setWindowSize(int rows, int cols) {
        if (closed || fd < 0) return;
        nativeSetWinSize(fd, Math.max(rows, 1), Math.max(cols, 1));
    }

    public int getForegroundPid() {
        if (fd < 0) return -1;
        return nativeGetForegroundPid(fd);
    }

    public void sendSignal(int signal) {
        if (pid > 0) {
            nativeSendSignal(pid, signal);
        }
    }

    public int waitFor() {
        if (pid <= 0) return -1;
        int status = nativeWaitFor(pid);
        pid = -1;
        return status;
    }

    public boolean isTerminal() {
        return fd >= 0 && nativeIsTerminal(fd);
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (pid > 0) {
            nativeSendSignal(pid, SIGTERM);
        }
        if (fd >= 0) {
            nativeClose(fd);
            fd = -1;
        }
        if (pid > 0) {
            // Reap promptly; the child dies when its PTY master goes away.
            nativeWaitFor(pid);
            pid = -1;
        }
    }

    private static native int[] nativeCreateSubprocess(String[] argv, String[] envp,
                                                       String cwd, int rows, int cols);

    private static native int nativeWaitFor(int pid);

    private static native int nativeRead(int fd, byte[] buffer, int offset, int length);

    private static native int nativeWrite(int fd, byte[] buffer, int offset, int length);

    private static native void nativeSetWinSize(int fd, int rows, int cols);

    private static native void nativeClose(int fd);

    private static native int nativeSendSignal(int pid, int signal);

    private static native int nativeGetForegroundPid(int fd);

    private static native boolean nativeIsTerminal(int fd);
}
