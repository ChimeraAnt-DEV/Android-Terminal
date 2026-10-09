package com.chimeraant.terminal.root;

import android.content.Context;
import android.os.Build;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.GZIPInputStream;

/**
 * Installs a userspace Linux rootfs that runs through {@code proot}.
 *
 * This is the only honest way to give an unrooted device (for example a 2021
 * Amazon Fire HD 10, 11th generation) a root shell: proot intercepts the
 * relevant syscalls in userspace, so the kernel never grants real uid 0, yet
 * everything inside the sandbox believes it is root and package managers work.
 */
public class ProotInstaller {

    public interface ProgressListener {
        void onProgress(String stage, int percent);

        void onComplete(boolean success, String message);
    }

    // Public, stable mirrors. Both are plain HTTPS downloads - no accounts.
    public static final String DEFAULT_PROOT_BASE =
            "https://github.com/termux/proot/releases/download/v5.1.107-1/proot-static";
    public static final String ALPINE_MIRROR =
            "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases";

    private final Context context;
    private final File installDir;
    private final File rootfsDir;
    private final File prootBinary;

    public ProotInstaller(Context context) {
        this.context = context.getApplicationContext();
        File base = new File(this.context.getFilesDir(), "proot");
        this.installDir = base;
        this.rootfsDir = new File(base, "rootfs");
        this.prootBinary = new File(base, "bin/proot");
    }

    public File getRootfsDir() {
        return rootfsDir;
    }

    public File getProotBinary() {
        return prootBinary;
    }

    public boolean isInstalled() {
        return prootBinary.exists() && new File(rootfsDir, "bin/sh").exists();
    }

    /** Map the current ABI to the arch string used by Alpine minirootfs. */
    public static String alpineArch() {
        for (String abi : Build.SUPPORTED_ABIS) {
            switch (abi) {
                case "arm64-v8a": return "aarch64";
                case "armeabi-v7a":
                case "armeabi": return "armv7";
                case "x86_64": return "x86_64";
                case "x86": return "x86";
                default: break;
            }
        }
        return "aarch64";
    }

    public static String alpineTarballName() {
        return "alpine-minirootfs-3.20.3-" + alpineArch() + ".tar.gz";
    }

    public void install(ProgressListener listener) {
        new Thread(() -> {
            try {
                if (!installDir.exists() && !installDir.mkdirs()) {
                    fail(listener, "Cannot create install directory");
                    return;
                }

                notify(listener, "Downloading proot", 5);
                File tmpProot = new File(installDir, "proot.download");
                boolean prootOk = downloadFile(DEFAULT_PROOT_BASE, tmpProot,
                        p -> notify(listener, "Downloading proot", 5 + p / 4));
                if (!prootOk && !prootBinary.exists()) {
                    // proot may be unavailable; still allow rootfs for later.
                    notify(listener, "proot download failed; will retry on demand", 30);
                } else if (prootOk) {
                    File binDir = prootBinary.getParentFile();
                    if (binDir != null && !binDir.exists()) binDir.mkdirs();
                    if (tmpProot.renameTo(prootBinary)) {
                        prootBinary.setExecutable(true, false);
                    }
                }

                notify(listener, "Downloading Alpine rootfs", 30);
                File tarball = new File(installDir, alpineTarballName());
                String url = ALPINE_MIRROR + "/" + alpineArch() + "/" + alpineTarballName();
                if (!tarball.exists()) {
                    boolean ok = downloadFile(url, tarball,
                            p -> notify(listener, "Downloading Alpine rootfs", 30 + p * 5 / 10));
                    if (!ok) {
                        fail(listener, "Rootfs download failed: " + url);
                        return;
                    }
                }

                notify(listener, "Extracting rootfs", 80);
                if (!rootfsDir.exists()) rootfsDir.mkdirs();
                extractTarGz(tarball, rootfsDir);

                notify(listener, "Finalising", 96);
                File resolv = new File(rootfsDir, "etc/resolv.conf");
                File resolvParent = resolv.getParentFile();
                if (resolvParent != null) resolvParent.mkdirs();

                if (prootBinary.exists() || new File(prootBinary.getAbsolutePath()).exists()) {
                    notify(listener, "Done", 100);
                    complete(listener, true, "Userspace root installed (" + alpineArch() + ").");
                } else {
                    complete(listener, true,
                            "Rootfs installed. proot binary missing; place a static proot at "
                                    + prootBinary.getAbsolutePath());
                }
            } catch (Exception e) {
                fail(listener, "Install failed: " + e.getMessage());
            }
        }, "proot-installer").start();
    }

    /** Build the argv that starts a login shell inside the proot sandbox. */
    public String[] prootLoginCommand(String[] shellCommand) {
        String proot = prootBinary.getAbsolutePath();
        String rootfs = rootfsDir.getAbsolutePath();
        String command = joinCommand(shellCommand);

        return new String[]{
                proot,
                "-0",
                "--link2symlink",
                "-r", rootfs,
                "-b", "/dev",
                "-b", "/dev/urandom:/dev/random",
                "-b", "/proc",
                "-b", "/sys",
                "-b", "/sdcard",
                "-b", "/storage",
                "-w", "/root",
                "/usr/bin/env", "-i",
                "HOME=/root",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "TERM=xterm-256color",
                "LANG=C.UTF-8",
                "/bin/sh", "-lc", command
        };
    }

    private static String joinCommand(String[] argv) {
        StringBuilder sb = new StringBuilder();
        for (String a : argv) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(a);
        }
        return sb.toString();
    }

    private interface ChunkProgress {
        void onPercent(int percent);
    }

    private boolean downloadFile(String urlString, File destination, ChunkProgress progress) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("User-Agent", "ChimeraTerminal/1.0");
            connection.connect();

            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location != null) return downloadFile(location, destination, progress);
                return false;
            }
            if (status != HttpURLConnection.HTTP_OK) {
                connection.disconnect();
                return false;
            }

            int total = connection.getContentLength();
            try (InputStream in = new BufferedInputStream(connection.getInputStream());
                 OutputStream out = new FileOutputStream(destination)) {
                byte[] buffer = new byte[65536];
                long read = 0;
                int n;
                int lastPercent = -1;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    read += n;
                    if (total > 0) {
                        int percent = (int) (read * 100 / total);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            if (progress != null) progress.onPercent(percent);
                        }
                    }
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** Minimal tar extractor; Android ships no tar binary. */
    private void extractTarGz(File tarball, File destination) throws IOException {
        try (InputStream raw = new FileInputStream(tarball);
             GZIPInputStream gzip = new GZIPInputStream(new BufferedInputStream(raw))) {
            byte[] header = new byte[512];
            while (true) {
                int read = readFully(gzip, header, 0, 512);
                if (read < 512) break;
                if (isZeroBlock(header)) break;

                String name = readString(header, 0, 100);
                long size = parseOctal(header, 124, 12);
                int typeFlag = header[156] & 0xFF;
                String prefix = readString(header, 345, 155);
                if (!prefix.isEmpty()) {
                    name = prefix + "/" + name;
                }
                name = sanitize(name);
                if (name.isEmpty()) {
                    skipFully(gzip, size);
                    continue;
                }

                File target = new File(destination, name);
                if (!isInside(destination, target)) {
                    skipFully(gzip, size);
                    continue;
                }

                switch (typeFlag) {
                    case '5': // directory
                        target.mkdirs();
                        break;
                    case '0':
                    case 0:
                    case 7: { // regular file
                        File parent = target.getParentFile();
                        if (parent != null) parent.mkdirs();
                        try (OutputStream out = new FileOutputStream(target)) {
                            copyN(gzip, out, size);
                        }
                        applyMode(header, target);
                        break;
                    }
                    case '2': { // symlink
                        String link = readString(header, 157, 100);
                        File parent = target.getParentFile();
                        if (parent != null) parent.mkdirs();
                        try {
                            java.nio.file.Files.deleteIfExists(target.toPath());
                            java.nio.file.Files.createSymbolicLink(target.toPath(),
                                    java.nio.file.Paths.get(link));
                        } catch (IOException | UnsupportedOperationException e) {
                            // Some filesystems forbid symlinks; write a stub file.
                            try (FileOutputStream out = new FileOutputStream(target)) {
                                out.write(link.getBytes("UTF-8"));
                            }
                        }
                        break;
                    }
                    default:
                        skipFully(gzip, size);
                        break;
                }

                long padding = (512 - (size % 512)) % 512;
                skipFully(gzip, padding);
            }
        }
    }

    private static void applyMode(byte[] header, File target) {
        long mode = parseOctal(header, 100, 8);
        boolean executable = (mode & 0111) != 0;
        if (executable || target.getName().equals("busybox")) {
            target.setExecutable(true, false);
        }
        boolean readable = (mode & 0444) != 0;
        target.setReadable(true, false);
        if (readable) {
            target.setReadable(true, false);
        }
    }

    private static int readFully(InputStream in, byte[] buffer, int offset, int length)
            throws IOException {
        int total = 0;
        while (total < length) {
            int n = in.read(buffer, offset + total, length - total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }

    private static void copyN(InputStream in, OutputStream out, long count) throws IOException {
        byte[] buffer = new byte[65536];
        long remaining = count;
        while (remaining > 0) {
            int toRead = (int) Math.min(buffer.length, remaining);
            int n = in.read(buffer, 0, toRead);
            if (n < 0) break;
            out.write(buffer, 0, n);
            remaining -= n;
        }
    }

    private static void skipFully(InputStream in, long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() < 0) break;
                remaining--;
            } else {
                remaining -= skipped;
            }
        }
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte b : block) {
            if (b != 0) return false;
        }
        return true;
    }

    private static String readString(byte[] block, int offset, int length) {
        int end = offset;
        int max = offset + length;
        while (end < max && block[end] != 0) end++;
        try {
            return new String(block, offset, end - offset, "UTF-8").trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static long parseOctal(byte[] block, int offset, int length) {
        long result = 0;
        int end = offset + length;
        for (int i = offset; i < end; i++) {
            byte b = block[i];
            if (b == 0 || b == ' ') continue;
            if (b < '0' || b > '7') break;
            result = result * 8 + (b - '0');
        }
        return result;
    }

    private static String sanitize(String name) {
        while (name.startsWith("./")) name = name.substring(2);
        while (name.startsWith("/")) name = name.substring(1);
        return name;
    }

    private static boolean isInside(File directory, File candidate) {
        try {
            String base = directory.getCanonicalPath();
            String path = candidate.getCanonicalPath();
            return path.equals(base) || path.startsWith(base + File.separator);
        } catch (IOException e) {
            return false;
        }
    }

    private static void notify(ProgressListener listener, String stage, int percent) {
        if (listener != null) {
            listener.onProgress(stage, Math.min(100, percent));
        }
    }

    private static void complete(ProgressListener listener, boolean success, String message) {
        if (listener != null) {
            listener.onComplete(success, message);
        }
    }

    private static void fail(ProgressListener listener, String message) {
        if (listener != null) {
            listener.onComplete(false, message);
        }
    }
}
