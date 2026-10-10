package com.chimeraant.terminal.debloat;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The debloating engine.
 *
 * It reports what is installed, classifies each package by risk, and performs
 * disable / uninstall / restore through whichever privilege backend is
 * available. Every action records what it did so it can be undone.
 *
 * What is achievable, honestly:
 *  - With Shizuku or root: uninstall or disable system apps for user 0, exactly
 *    like {@code adb shell pm uninstall --user 0}. This is the same operation
 *    used by desktop tools, and it does not touch the system partition, so it
 *    does not brick the device and survives an OTA. Apps can be restored.
 *  - Without either: only listing works. Disabling and uninstalling require
 *    the shell or root identity; Android does not allow an ordinary app to do
 *    it, and no workaround changes that.
 */
public class DebloatEngine {

    private static final String TAG = "DebloatEngine";

    public static class AppEntry {
        public final String packageName;
        public final String label;
        public final boolean system;
        public final boolean enabled;
        public final DebloatSafety.Risk risk;

        public AppEntry(String packageName, String label, boolean system,
                        boolean enabled, DebloatSafety.Risk risk) {
            this.packageName = packageName;
            this.label = label;
            this.system = system;
            this.enabled = enabled;
            this.risk = risk;
        }

        @Override
        public String toString() {
            return label + " (" + packageName + ")";
        }
    }

    public interface ProgressListener {
        void onProgress(String stage, int percent);

        void onComplete(boolean success, String message);
    }

    private final Context context;
    private final PackageManager packageManager;

    public DebloatEngine(Context context) {
        this.context = context.getApplicationContext();
        this.packageManager = this.context.getPackageManager();
    }

    /** Enumerate installed apps with labels and risk classification. */
    public List<AppEntry> listInstalled(boolean includeSystem) {
        List<AppEntry> entries = new ArrayList<>();
        List<PackageInfo> packages;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packages = packageManager.getInstalledPackages(
                    PackageManager.PackageInfoFlags.of(0));
        } else {
            packages = packageManager.getInstalledPackages(0);
        }
        for (PackageInfo info : packages) {
            if (info.applicationInfo == null) continue;
            boolean system = (info.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if (system && !includeSystem) continue;
            boolean enabled = info.applicationInfo.enabled;
            String label = safeLabel(info);
            entries.add(new AppEntry(info.packageName, label, system, enabled,
                    DebloatSafety.classify(info.packageName)));
        }
        Collections.sort(entries, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return entries;
    }

    private String safeLabel(PackageInfo info) {
        try {
            CharSequence label = packageManager.getApplicationLabel(info.applicationInfo);
            return label == null ? info.packageName : label.toString();
        } catch (Exception e) {
            return info.packageName;
        }
    }

    /** Apps that are currently disabled for this user and could be restored. */
    public List<String> listDisabled() {
        List<String> disabled = new ArrayList<>();
        for (AppEntry entry : listInstalled(true)) {
            if (!entry.enabled) disabled.add(entry.packageName);
        }
        return disabled;
    }

    // -------------------------------------------------------------- actions

    public void disable(CommandRunner runner, String packageName, ProgressListener listener) {
        runAction(runner, "disable", packageName, listener);
    }

    public void uninstall(CommandRunner runner, String packageName, ProgressListener listener) {
        runAction(runner, "uninstall", packageName, listener);
    }

    public void restore(CommandRunner runner, String packageName, ProgressListener listener) {
        runAction(runner, "restore", packageName, listener);
    }

    public void enable(CommandRunner runner, String packageName, ProgressListener listener) {
        runAction(runner, "enable", packageName, listener);
    }

    private void runAction(CommandRunner runner, String action, String packageName,
                           ProgressListener listener) {
        new Thread(() -> {
            try {
                if (DebloatSafety.isProtected(packageName)) {
                    complete(listener, false, "Blocked: " + DebloatSafety.explain(packageName));
                    return;
                }
                if (runner == null || !runner.isAvailable()) {
                    String reason = runner == null
                            ? "No privilege backend available."
                            : runner.unavailableReason();
                    complete(listener, false,
                            "Cannot " + action + " without ADB-level or root access. " + reason);
                    return;
                }

                notify(listener, action + " " + packageName, 20);
                CommandRunner.Result result;
                if (runner instanceof ShizukuCommandRunner) {
                    result = runner.run(ShizukuCommandRunner.pmArgs(action, packageName));
                } else {
                    result = runner.run(buildPmArgs(action, packageName));
                }

                if (result.isSuccess()) {
                    DebloatHistory.record(context, action, packageName);
                    complete(listener, true, "OK: " + action + " " + packageName);
                } else {
                    complete(listener, false, "Failed: " + result.combined());
                }
            } catch (Exception e) {
                Log.e(TAG, "action failed", e);
                complete(listener, false, "Failed: " + e.getMessage());
            }
        }, "debloat-action").start();
    }

    /** argv for a shell/root backend. */
    private String[] buildPmArgs(String action, String packageName) {
        switch (action) {
            case "uninstall":
                return new String[]{"pm", "uninstall", "-k", "--user", "0", packageName};
            case "disable":
                return new String[]{"pm", "disable-user", "--user", "0", packageName};
            case "enable":
                return new String[]{"pm", "enable", packageName};
            case "restore":
                return new String[]{"pm", "install-existing", "--user", "0", packageName};
            default:
                throw new IllegalArgumentException("Unknown action: " + action);
        }
    }

    /** Disable every package classified SAFE from the supplied list. */
    public void bulkDisable(CommandRunner runner, List<String> packages,
                            ProgressListener listener) {
        new Thread(() -> {
            int done = 0;
            int failed = 0;
            int skipped = 0;
            int total = Math.max(1, packages.size());
            for (int i = 0; i < packages.size(); i++) {
                String pkg = packages.get(i);
                if (!DebloatSafety.isBulkSafe(pkg)) {
                    skipped++;
                    continue;
                }
                CommandRunner.Result result = runner.run(buildPmArgs("disable", pkg));
                if (result.isSuccess()) {
                    done++;
                    DebloatHistory.record(context, "disable", pkg);
                } else {
                    failed++;
                }
                notify(listener, "Disabling " + pkg, 10 + (i * 80 / total));
            }
            complete(listener, true, "Disabled " + done + ", failed " + failed
                    + ", skipped " + skipped + " protected packages.");
        }, "debloat-bulk").start();
    }

    private static void notify(ProgressListener listener, String stage, int percent) {
        if (listener != null) listener.onProgress(stage, Math.min(100, percent));
    }

    private static void complete(ProgressListener listener, boolean success, String message) {
        if (listener != null) listener.onComplete(success, message);
    }
}
