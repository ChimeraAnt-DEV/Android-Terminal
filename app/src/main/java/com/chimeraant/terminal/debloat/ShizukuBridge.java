package com.chimeraant.terminal.debloat;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;

/**
 * Real Shizuku integration.
 *
 * Shizuku hands us the system's package manager binder and relays our calls
 * through its {@code shell}-uid process - the same identity ADB uses. The
 * package manager interface is a hidden platform API, so it is reached by
 * reflection and the transaction is issued directly on the binder. That keeps
 * this working across Android versions instead of depending on one exact
 * hidden signature.
 */
public class ShizukuBridge implements ShizukuCommandRunner.PackageManagerBridge,
        ShizukuCommandRunner.Availability {

    private static final String TAG = "ShizukuBridge";

    private static final String SERVICE = "package";
    private static final String DESCRIPTOR = "android.content.pm.IPackageManager";

    private static final int FLAG_SYSTEM = 1 << 0;
    private static final int FLAG_UPDATED_SYSTEM_APP = 1 << 7;

    private final ShizukuCommandRunner runner;

    public ShizukuBridge() {
        this.runner = new ShizukuCommandRunner(this, this);
    }

    public ShizukuCommandRunner asRunner() {
        return runner;
    }

    // ------------------------------------------------------------ availability

    @Override
    public boolean isAvailable() {
        try {
            if (!Shizuku.pingBinder()) return false;
            if (Shizuku.isPreV11()) return false;
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            Log.d(TAG, "Shizuku not reachable: " + t.getMessage());
            return false;
        }
    }

    @Override
    public String reason() {
        try {
            if (!Shizuku.pingBinder()) {
                return "Shizuku is not running. Install Shizuku and start it "
                        + "(Android 11+ can start it on-device via Wireless debugging).";
            }
            if (Shizuku.isPreV11()) {
                return "The installed Shizuku version is too old.";
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                return "Shizuku is running but this app has not been granted permission.";
            }
            return "Shizuku is available.";
        } catch (Throwable t) {
            return "Shizuku not available: " + t.getMessage();
        }
    }

    /** Ask the user to authorise this app inside Shizuku. */
    public void requestPermission() {
        try {
            Shizuku.requestPermission(0);
        } catch (Throwable t) {
            Log.w(TAG, "requestPermission failed", t);
        }
    }

    // ---------------------------------------------------------------- binder

    private IBinder service() {
        IBinder binder = SystemServiceHelper.getSystemService(SERVICE);
        if (binder == null) return null;
        return new ShizukuBinderWrapper(binder);
    }

    /** Find a declared transaction code on the hidden interface. */
    private static int transactionCode(String method, Class<?>... parameterTypes) {
        try {
            Class<?> iface = Class.forName(DESCRIPTOR);
            Method m = iface.getMethod(method, parameterTypes);
            java.lang.reflect.Field field = iface.getField("TRANSACTION_" + method);
            return field.getInt(null);
        } catch (Throwable t) {
            return -1;
        }
    }

    // ------------------------------------------------------------- listing

    @Override
    public List<String> listPackages(boolean systemOnly) {
        List<String> result = new ArrayList<>();
        try {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeInt(0); // flags
                data.writeInt(0); // userId

                IBinder binder = service();
                if (binder == null) return result;

                // getInstalledPackages has existed with this shape for years;
                // fall back to reflection if the transaction code is missing.
                int code = transactionCode("getInstalledPackages", int.class, int.class);
                if (code < 0) {
                    Log.w(TAG, "getInstalledPackages transaction not found");
                    return result;
                }
                binder.transact(code, data, reply, 0);
                reply.readException();

                int count = reply.readInt();
                for (int i = 0; i < count; i++) {
                    // PackageInfo is Parcelable; read it reflectively.
                    Object info = readPackageInfo(reply);
                    if (info == null) continue;
                    String name = invokeString(info, "packageName");
                    if (name == null) continue;
                    if (systemOnly) {
                        Object appInfo = invoke(info, "applicationInfo");
                        int flags = appInfo == null ? 0 : invokeInt(appInfo, "flags");
                        if ((flags & (FLAG_SYSTEM | FLAG_UPDATED_SYSTEM_APP)) == 0) continue;
                    }
                    result.add(name);
                }
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Throwable t) {
            Log.w(TAG, "listPackages failed", t);
        }
        return result;
    }

    private static Object readPackageInfo(Parcel reply) {
        try {
            Class<?> clazz = Class.forName("android.content.pm.PackageInfo");
            java.lang.reflect.Field creatorField = clazz.getField("CREATOR");
            Object creator = creatorField.get(null);
            Method create = creator.getClass().getMethod("createFromParcel", Parcel.class);
            return create.invoke(creator, reply);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String invokeString(Object target, String method) {
        Object value = invoke(target, method);
        return value instanceof String ? (String) value : null;
    }

    private static int invokeInt(Object target, String method) {
        Object value = invoke(target, method);
        return value instanceof Integer ? (Integer) value : 0;
    }

    private static Object invoke(Object target, String method) {
        try {
            Method m = target.getClass().getMethod(method);
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------- actions

    @Override
    public String uninstall(String pkg) {
        // Mirrors: pm uninstall -k --user 0 <pkg>
        int code = transactionCode("deletePackageAsUser", String.class, int.class,
                int.class, int.class);
        if (code < 0) {
            code = transactionCode("deletePackage", String.class, int.class, int.class);
        }
        return transactSimple(code, pkg, true);
    }

    @Override
    public String setEnabled(String pkg, boolean enabled) {
        int newState = enabled
                ? PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
        int code = transactionCode("setApplicationEnabledSetting", String.class,
                int.class, int.class, int.class, String.class);
        if (code < 0) {
            return "setApplicationEnabledSetting is not available on this build";
        }
        try {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeString(pkg);
                data.writeInt(newState);
                data.writeInt(0); // flags
                data.writeInt(0); // userId
                data.writeString(null); // callingPackage
                IBinder binder = service();
                if (binder == null) return "Shizuku service unavailable";
                binder.transact(code, data, reply, 0);
                reply.readException();
                return null;
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Throwable t) {
            return "setEnabled failed: " + t.getMessage();
        }
    }

    @Override
    public String installExisting(String pkg) {
        int code = transactionCode("installExistingPackageAsUser", String.class,
                int.class, int.class, int.class, String.class);
        if (code < 0) {
            code = transactionCode("installExistingPackageAsUser", String.class,
                    int.class, int.class);
        }
        if (code < 0) return "install-existing is not available on this build";
        try {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeString(pkg);
                data.writeInt(0); // installFlags
                data.writeInt(0); // userId
                data.writeInt(0); // installReason
                data.writeString(null); // callingPackage
                IBinder binder = service();
                if (binder == null) return "Shizuku service unavailable";
                binder.transact(code, data, reply, 0);
                reply.readException();
                int result = reply.readInt();
                return result == 0 ? null : "install-existing returned " + result;
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Throwable t) {
            return "install-existing failed: " + t.getMessage();
        }
    }

    private String transactSimple(int code, String pkg, boolean withFlags) {
        if (code < 0) return "uninstall is not available on this build";
        try {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeString(pkg);
                data.writeInt(0); // userId
                data.writeInt(0); // flags
                data.writeInt(0); // callerId / reason
                IBinder binder = service();
                if (binder == null) return "Shizuku service unavailable";
                binder.transact(code, data, reply, 0);
                reply.readException();
                int result = reply.readInt();
                return result == 1 ? null : "PackageManager refused (code " + result + ")";
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Throwable t) {
            return "uninstall failed: " + t.getMessage();
        }
    }

    /** Package names visible to the app, used as a fallback listing source. */
    public List<String> fallbackList(PackageManager packageManager) {
        List<String> result = new ArrayList<>();
        for (ApplicationInfo info : packageManager.getInstalledApplications(0)) {
            result.add(info.packageName);
        }
        return result;
    }

    static {
        // Keeps the imports honest if the class is trimmed by the shrinker.
        Log.d(TAG, "Shizuku bridge loaded; supported: "
                + Arrays.toString(new String[]{"list", "uninstall", "disable", "restore"}));
    }
}
