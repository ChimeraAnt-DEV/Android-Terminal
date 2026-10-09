package com.chimeraant.terminal.root;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Central place for everything privileged.
 *
 * Two completely different things are called "root" in terminal apps and the
 * UI must not blur them:
 *
 *  - <b>Device root</b> - an actual {@code su} from Magisk / KernelSU / a
 *    factory-engineered ROM. When present we can run commands as uid 0.
 *  - <b>Userspace root</b> - a {@code proot} sandbox that fakes uid 0 inside a
 *    chroot without touching the real kernel. This works on every unrooted
 *    device, including a 2021 Amazon Fire tablet.
 *
 * The toggle the user sees picks the best available mode and always tells the
 * truth about what it actually achieved.
 */
public class RootManager {

    private static final String TAG = "RootManager";
    private static final String PREFS = "chimera_root";
    private static final String KEY_ENABLED = "root_enabled";
    private static final String KEY_MODE = "root_mode";

    public enum Mode {
        /** No elevation available. */
        NONE,
        /** Real device su (Magisk/KernelSU/engineering ROM). */
        DEVICE_SU,
        /** proot-based fake root, works anywhere. */
        PROOT
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final List<String> suCandidates = new ArrayList<>();

    private volatile Mode mode = Mode.NONE;
    private volatile boolean rootGranted = false;
    private volatile String rootIdentity = "";

    public RootManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        collectSuCandidates();
        mode = Mode.valueOf(prefs.getString(KEY_MODE, Mode.NONE.name()));
    }

    private void collectSuCandidates() {
        suCandidates.add("su");
        suCandidates.add("/system/bin/su");
        suCandidates.add("/system/xbin/su");
        suCandidates.add("/sbin/su");
        suCandidates.add("/su/bin/su");
        suCandidates.add("/magisk/.core/bin/su");
        suCandidates.add("/debug_ramdisk/su");
        suCandidates.add("/data/adb/ksu/bin/su");
        suCandidates.add("/data/adb/magisk/su");
    }

    public boolean isRootEnabled() {
        return prefs.getBoolean(KEY_ENABLED, false);
    }

    public Mode getMode() {
        return mode;
    }

    public boolean isRootGranted() {
        return rootGranted;
    }

    public String getRootIdentity() {
        return rootIdentity;
    }

    public String getSuPath() {
        for (String candidate : suCandidates) {
            if (new File(candidate).exists()) {
                return candidate;
            }
        }
        return null;
    }

    public boolean hasProot() {
        return new File(context.getFilesDir(), "usr/bin/proot").exists()
                || new File(context.getApplicationInfo().nativeLibraryDir, "libproot.so").exists();
    }

    public boolean isAmazonFireDevice() {
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase();
        String brand = Build.BRAND == null ? "" : Build.BRAND.toLowerCase();
        String model = Build.MODEL == null ? "" : Build.MODEL.toLowerCase();
        return manufacturer.contains("amazon")
                || brand.contains("amazon")
                || model.contains("kf")
                || model.contains("kindle")
                || model.contains("fire");
    }

    /** Human readable description of what the toggle would give this device. */
    public String describeCapability() {
        if (getSuPath() != null) {
            return "Device root available (su found at " + getSuPath() + ").";
        }
        if (hasProot()) {
            return "Userspace root available via proot (works on any device).";
        }
        return "No root yet. A proot rootfs can be downloaded for unrooted devices.";
    }

    /** Request device root asynchronously. */
    public void requestDeviceRoot(Callback callback) {
        Executors.newSingleThreadExecutor().execute(() -> {
            Mode detected = detect();
            boolean granted = false;
            String identity = "";
            if (detected == Mode.DEVICE_SU) {
                String output = runSu("id");
                if (output != null && output.contains("uid=0")) {
                    granted = true;
                    identity = output.trim();
                }
            }
            mode = granted ? Mode.DEVICE_SU : (hasProot() ? Mode.PROOT : Mode.NONE);
            rootGranted = granted;
            rootIdentity = identity;
            persist();
            if (callback != null) {
                callback.onResult(granted, mode, describeCapability());
            }
        });
    }

    private Mode detect() {
        String suPath = getSuPath();
        if (suPath != null) {
            return Mode.DEVICE_SU;
        }
        String magisk = runProcess(new String[]{"which", "magisk"});
        if (magisk != null && !magisk.isEmpty()) {
            return Mode.DEVICE_SU;
        }
        if (hasProot()) {
            return Mode.PROOT;
        }
        return Mode.NONE;
    }

    /** Runs a shell command through su, returning stdout or null. */
    public String runSu(String command) {
        String suPath = getSuPath();
        if (suPath == null) return null;
        return runProcess(new String[]{suPath, "-c", command});
    }

    private String runProcess(String[] argv) {
        Process process = null;
        try {
            process = new ProcessBuilder(argv).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            process.waitFor();
            return sb.toString();
        } catch (IOException | InterruptedException e) {
            Log.d(TAG, "command failed: " + e.getMessage());
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        } finally {
            if (process != null) process.destroy();
        }
    }

    /** Enable/disable the root preference. Returns the resulting mode. */
    public Mode setEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
        if (!enabled) {
            mode = Mode.NONE;
            rootGranted = false;
            rootIdentity = "";
            persist();
        }
        return mode;
    }

    private void persist() {
        prefs.edit().putString(KEY_MODE, mode.name()).apply();
    }

    public String[] rootedCommandFor(String[] original) {
        if (!isRootEnabled()) return original;
        String suPath = getSuPath();
        if (suPath == null) return original;
        String[] wrapped = new String[original.length + 2];
        wrapped[0] = suPath;
        wrapped[1] = "-c";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < original.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(shellQuote(original[i]));
        }
        wrapped[2] = sb.toString();
        return wrapped;
    }

    private static String shellQuote(String value) {
        if (value.isEmpty()) return "''";
        if (value.matches("[A-Za-z0-9_./=:@%+-]+")) return value;
        return "'" + value.replace("'", "'\\''") + "'";
    }

    public interface Callback {
        void onResult(boolean granted, Mode mode, String message);
    }
}
