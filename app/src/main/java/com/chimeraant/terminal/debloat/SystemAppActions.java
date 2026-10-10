package com.chimeraant.terminal.debloat;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;

/**
 * Actions that work with no special access at all.
 *
 * Android lets any app send the user to the system screens for an app, where
 * they can force stop it, disable it (on some builds) or remove updates. This
 * is the honest fallback when neither Shizuku nor root is available: the app
 * cannot press the buttons for the user, so it takes them to the right screen.
 */
public final class SystemAppActions {

    private static final String TAG = "SystemAppActions";

    private SystemAppActions() {
    }

    /** Open the system "App info" screen for a package. */
    public static boolean openAppInfo(Context context, String packageName) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", packageName, null));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return start(context, intent);
    }

    /**
     * Open the system uninstall screen. Android only offers this for apps the
     * user installed; for preinstalled apps it opens App info instead.
     */
    public static boolean openUninstall(Context context, String packageName) {
        Intent intent = new Intent(Intent.ACTION_DELETE);
        intent.setData(Uri.fromParts("package", packageName, null));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (start(context, intent)) return true;
        return openAppInfo(context, packageName);
    }

    /** Open the list of apps so the user can browse manually. */
    public static boolean openAppList(Context context) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (start(context, intent)) return true;
        intent = new Intent(Settings.ACTION_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return start(context, intent);
    }

    /** Open Android's own storage screen, useful for cleanup. */
    public static boolean openStorageSettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return start(context, intent);
    }

    private static boolean start(Context context, Intent intent) {
        try {
            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "cannot open system screen: " + e.getMessage());
            return false;
        }
    }
}
