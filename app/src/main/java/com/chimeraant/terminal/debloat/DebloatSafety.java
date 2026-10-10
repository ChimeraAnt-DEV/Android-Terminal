package com.chimeraant.terminal.debloat;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Guards against the classic ways people brick or break a device while
 * debloating. Removing any of these breaks the launcher, the system UI, the
 * package manager, connectivity or the ability to boot.
 *
 * The list is intentionally conservative: it blocks only things that are known
 * to cause a non-booting device or an unusable UI.
 */
public final class DebloatSafety {

    public enum Risk {
        /** Never remove; the device may not boot or the UI will break. */
        CRITICAL,
        /** Removing breaks a major feature; allowed only after a warning. */
        CAUTION,
        /** Ordinary bloat; safe to remove. */
        SAFE
    }

    private static final Set<String> CRITICAL = new HashSet<>(Arrays.asList(
            // Core system / boot
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.shell",
            "com.android.providers.settings",
            "com.android.providers.media",
            "com.android.providers.downloads",
            "com.android.providers.contacts",
            "com.android.providers.telephony",
            "com.android.keychain",
            "com.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.android.server.telecom",
            "com.android.phone",
            "com.android.bluetooth",
            "com.android.nfc",
            "com.android.wifi",
            "com.android.se",
            "com.android.launcher3",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.android.vending",
            // Amazon Fire OS essentials: launcher, settings, setup, webview and
            // the OTA client. Removing these breaks boot, the UI or updates.
            // Other Amazon apps (Prime Video, Appstore, DRM) fall through to
            // CAUTION via the com.amazon. prefix rule below.
            "com.amazon.device.software.ota",
            "com.amazon.kindle.otter.oobe",
            "com.amazon.firelauncher",
            "com.amazon.tv.launcher",
            "com.amazon.webview",
            "com.amazon.cloud9",
            "com.amazon.device.settings",
            "com.amazon.device.setupwizard"
    ));

    private static final Set<String> CAUTION_PREFIXES = new HashSet<>(Arrays.asList(
            "com.google.android.apps.",
            "com.android.vending",
            "com.google.android.gms",
            "com.samsung.android.",
            "com.miui.",
            "com.amazon."
    ));

    private DebloatSafety() {
    }

    public static Risk classify(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return Risk.CAUTION;
        }
        if (CRITICAL.contains(packageName)) {
            return Risk.CRITICAL;
        }
        for (String prefix : CAUTION_PREFIXES) {
            if (packageName.startsWith(prefix)) {
                return Risk.CAUTION;
            }
        }
        return Risk.SAFE;
    }

    public static boolean isProtected(String packageName) {
        return classify(packageName) == Risk.CRITICAL;
    }

    /** Plain-language explanation shown before a risky action. */
    public static String explain(String packageName) {
        switch (classify(packageName)) {
            case CRITICAL:
                return packageName + " is a core system component. Removing it can "
                        + "prevent the device from booting or leave it without a "
                        + "launcher, settings or network. This action is blocked.";
            case CAUTION:
                return packageName + " is a system app. Removing it may disable a "
                        + "feature (accounts, updates, casting). You can restore it "
                        + "later with Restore.";
            default:
                return packageName + " is ordinary bloat and is safe to remove.";
        }
    }

    /** Categories that are always safe to disable in bulk. */
    public static boolean isBulkSafe(String packageName) {
        return classify(packageName) == Risk.SAFE;
    }
}
