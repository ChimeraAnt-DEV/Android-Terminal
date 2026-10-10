package com.chimeraant.terminal.termux;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads and unpacks the official Termux bootstrap archive.
 *
 * The archive contains Android-native binaries (they link against
 * {@code /system/bin/linker64} and {@code /apex/.../libc.so}), so they execute
 * directly on the device - no emulation layer is involved. The one catch is
 * that Termux binaries hardcode their prefix as
 * {@code /data/data/com.termux/files/usr}; that path only exists for the Termux
 * package, so the shell is launched through {@code proot} with this sandbox's
 * prefix bound onto it. See {@link #loginCommand}.
 */
public class TermuxBootstrapInstaller {

    public interface ProgressListener {
        void onProgress(String stage, int percent);

        void onComplete(boolean success, String message);
    }

    private static final String TAG = "TermuxBootstrap";

    /** Pinned release; verified reachable and small enough for a tablet download. */
    public static final String BOOTSTRAP_TAG = "bootstrap-2021.02.19-r1";
    public static final String BOOTSTRAP_BASE =
            "https://github.com/termux/termux-packages/releases/download/" + BOOTSTRAP_TAG + "/";

    static final String TERMUX_PREFIX = "/data/data/com.termux/files/usr";
    static final String TERMUX_HOME = "/data/data/com.termux/files/home";

    private final Context context;
    private final File termuxDir;
    private final File prefixDir;
    private final File homeDir;

    public TermuxBootstrapInstaller(Context context) {
        this.context = context.getApplicationContext();
        this.termuxDir = new File(this.context.getFilesDir(), "termux");
        this.prefixDir = new File(termuxDir, "usr");
        this.homeDir = new File(termuxDir, "home");
    }

    public File getPrefixDir() {
        return prefixDir;
    }

    public File getHomeDir() {
        return homeDir;
    }

    public boolean isInstalled() {
        return new File(prefixDir, "bin/bash").exists()
                || new File(prefixDir, "bin/login").exists();
    }

    public static String bootstrapAssetName() {
        return "bootstrap-" + archName() + ".zip";
    }

    /** Termux architecture naming. */
    public static String archName() {
        String[] abis = Build.SUPPORTED_ABIS;
        if (abis == null || abis.length == 0) {
            return "aarch64";
        }
        for (String abi : abis) {
            if (abi == null) continue;
            switch (abi) {
                case "arm64-v8a": return "aarch64";
                case "armeabi-v7a":
                case "armeabi": return "arm";
                case "x86_64": return "x86_64";
                case "x86": return "i686";
                default: break;
            }
        }
        return "aarch64";
    }

    public void install(ProgressListener listener) {
        new Thread(() -> {
            try {
                if (!termuxDir.exists() && !termuxDir.mkdirs()) {
                    fail(listener, "Cannot create " + termuxDir.getAbsolutePath());
                    return;
                }
                if (!prefixDir.exists() && !prefixDir.mkdirs()) {
                    fail(listener, "Cannot create " + prefixDir.getAbsolutePath());
                    return;
                }
                if (!homeDir.exists() && !homeDir.mkdirs()) {
                    fail(listener, "Cannot create " + homeDir.getAbsolutePath());
                    return;
                }

                String asset = bootstrapAssetName();
                String url = BOOTSTRAP_BASE + asset;
                File archive = new File(termuxDir, asset);

                if (!archive.exists() || archive.length() == 0) {
                    notify(listener, "Downloading Termux bootstrap (" + archName() + ")", 5);
                    boolean ok = download(url, archive, p -> notify(listener,
                            "Downloading Termux bootstrap", 5 + p * 6 / 10));
                    if (!ok) {
                        fail(listener, "Download failed: " + url);
                        return;
                    }
                } else {
                    notify(listener, "Using cached bootstrap archive", 65);
                }

                notify(listener, "Unpacking packages", 70);
                int entries = extractZip(archive, prefixDir);
                if (entries <= 0) {
                    fail(listener, "Archive contained no files");
                    return;
                }

                notify(listener, "Creating symlinks", 88);
                int links = createSymlinks(prefixDir);

                notify(listener, "Writing profile", 94);
                writeProfileFiles();
                makeExecutable(new File(prefixDir, "bin"));

                notify(listener, "Done", 100);
                complete(listener, true, "Termux bootstrap installed: "
                        + entries + " files, " + links + " symlinks (" + archName() + ").");
            } catch (Exception e) {
                Log.e(TAG, "install failed", e);
                fail(listener, "Install failed: " + e.getMessage());
            }
        }, "termux-bootstrap").start();
    }

    /**
     * Build the argv that launches a login shell inside the Termux prefix.
     *
     * @param prootPath absolute path to the static proot binary
     */
    public String[] loginCommand(String prootPath) {
        String rootfs = termuxDir.getAbsolutePath();
        String prefix = prefixDir.getAbsolutePath();
        String home = homeDir.getAbsolutePath();

        // Bind the real prefix over the path Termux binaries were compiled for.
        return new String[]{
                prootPath,
                "--link2symlink",
                "-r", rootfs,
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys",
                "-b", "/sdcard",
                "-b", "/storage",
                "-b", prefix + ":" + TERMUX_PREFIX,
                "-b", home + ":" + TERMUX_HOME,
                "-w", home,
                "/usr/bin/env", "-i",
                "HOME=" + TERMUX_HOME,
                "PREFIX=" + TERMUX_PREFIX,
                "PATH=" + TERMUX_PREFIX + "/bin:/system/bin:/system/xbin",
                "TERM=xterm-256color",
                "COLORTERM=truecolor",
                "LANG=en_US.UTF-8",
                "LD_PRELOAD=" + TERMUX_PREFIX + "/lib/libtermux-exec.so",
                "TMPDIR=" + TERMUX_PREFIX + "/tmp",
                TERMUX_PREFIX + "/bin/login"
        };
    }

    /** Like {@link #loginCommand} but runs a specific shell instead of login. */
    public String[] shellCommand(String prootPath, String shellName) {
        String[] login = loginCommand(prootPath);
        String[] result = login.clone();
        result[result.length - 1] = TERMUX_PREFIX + "/bin/" + shellName;
        return result;
    }

    private void writeProfileFiles() throws IOException {
        File bashrc = new File(homeDir, ".bashrc");
        if (!bashrc.exists() || bashrc.length() == 0) {
            writeFile(bashrc,
                    "# Chimera Terminal - Termux sandbox\n"
                            + "export PS1='\\[\\e[38;2;127;212;255m\\]\\u@chimera\\[\\e[0m\\] "
                            + "\\W \\$ '\n"
                            + "export EDITOR=vi\n"
                            + "export PAGER=less\n"
                            + "alias ll='ls -la'\n"
                            + "alias la='ls -A'\n"
                            + "alias cls='clear'\n");
        }
        File profile = new File(homeDir, ".profile");
        if (!profile.exists() || profile.length() == 0) {
            writeFile(profile, "[ -f ~/.bashrc ] && . ~/.bashrc\n");
        }
        File motd = new File(prefixDir, "etc/motd");
        File motdParent = motd.getParentFile();
        if (motdParent != null && !motdParent.exists()) motdParent.mkdirs();
        writeFile(motd,
                "\u001B[38;2;127;212;255mChimera Terminal\u001B[0m - Termux userland ("
                        + archName() + ")\n"
                        + "Run 'pkg update' then 'pkg install <name>'. 'pkg' and 'apt' are real here.\n");
    }

    // ------------------------------------------------------------------- zip

    private int extractZip(File archive, File destination) throws IOException {
        int count = 0;
        byte[] buffer = new byte[65536];
        try (ZipInputStream zip = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = sanitize(entry.getName());
                if (name.isEmpty()) {
                    zip.closeEntry();
                    continue;
                }
                File target = new File(destination, name);
                if (!isInside(destination, target)) {
                    zip.closeEntry();
                    continue;
                }
                if (entry.isDirectory()) {
                    if (!target.exists()) target.mkdirs();
                } else {
                    File parent = target.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    try (OutputStream out = new FileOutputStream(target)) {
                        int n;
                        while ((n = zip.read(buffer)) > 0) {
                            out.write(buffer, 0, n);
                        }
                    }
                    if (name.startsWith("bin/")) {
                        target.setExecutable(true, false);
                    }
                    count++;
                }
                zip.closeEntry();
            }
        }
        return count;
    }

    /** The archive ships a SYMLINKS.txt mapping "target\u2190link". */
    private int createSymlinks(File root) {
        File symlinksFile = new File(root, "SYMLINKS.txt");
        if (!symlinksFile.exists()) return 0;
        int created = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(symlinksFile)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int arrow = line.indexOf('\u2190');
                if (arrow <= 0) continue;
                String target = line.substring(0, arrow);
                String link = line.substring(arrow + 1);
                if (link.startsWith("./")) link = link.substring(2);
                File linkFile = new File(root, link);
                File parent = linkFile.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                try {
                    if (linkFile.exists()) linkFile.delete();
                    java.nio.file.Files.createSymbolicLink(
                            linkFile.toPath(), java.nio.file.Paths.get(target));
                    created++;
                } catch (Exception e) {
                    String cleanTarget = target.startsWith("./") ? target.substring(2) : target;
                    File source = new File(root, cleanTarget);
                    if (source.exists()) {
                        try {
                            copyFile(source, linkFile);
                            created++;
                        } catch (IOException ignored) {
                            // Nothing more we can do for this entry.
                        }
                    }
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "symlink pass failed: " + e.getMessage());
        }
        symlinksFile.delete();
        return created;
    }

    private void makeExecutable(File dir) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                makeExecutable(child);
            } else {
                child.setExecutable(true, false);
            }
        }
    }

    // ------------------------------------------------------------- utilities

    private interface ChunkProgress {
        void onPercent(int percent);
    }

    private boolean download(String urlString, File destination, ChunkProgress progress) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(urlString).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("User-Agent", "ChimeraTerminal/1.0");
            connection.connect();

            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                return location != null && download(location, destination, progress);
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
            Log.w(TAG, "download failed: " + e.getMessage());
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void copyFile(File source, File destination) throws IOException {
        try (InputStream in = new FileInputStream(source);
             OutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
        }
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

    private static void writeFile(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes("UTF-8"));
        }
    }

    private static void notify(ProgressListener listener, String stage, int percent) {
        if (listener != null) listener.onProgress(stage, Math.min(100, percent));
    }

    private static void complete(ProgressListener listener, boolean success, String message) {
        if (listener != null) listener.onComplete(success, message);
    }

    private static void fail(ProgressListener listener, String message) {
        if (listener != null) listener.onComplete(false, message);
    }
}
