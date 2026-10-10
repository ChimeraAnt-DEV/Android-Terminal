package com.chimeraant.terminal.debloat;

import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Executes package operations through Shizuku.
 *
 * Shizuku runs a process with the {@code shell} uid (2000) - the same identity
 * ADB uses - and lets this app call system services through it. On Android 11
 * and newer the user can start Shizuku entirely on-device via Wireless
 * debugging, so no PC is required.
 *
 * The app talks to the package manager service directly rather than shelling
 * out to {@code pm}, because an app process cannot fork a shell-uid child.
 */
public class ShizukuCommandRunner implements CommandRunner {

    private static final String TAG = "ShizukuRunner";

    /** Reports whether Shizuku is running and authorised. */
    public interface Availability {
        boolean isAvailable();

        String reason();
    }

    /** Operations performed against the system package manager service. */
    public interface PackageManagerBridge {
        List<String> listPackages(boolean systemOnly) throws RemoteException;

        /** @return null on success, or an error message */
        String uninstall(String pkg) throws RemoteException;

        /** @return null on success, or an error message */
        String setEnabled(String pkg, boolean enabled) throws RemoteException;

        /** @return null on success, or an error message */
        String installExisting(String pkg) throws RemoteException;
    }

    private final Availability availability;
    private final PackageManagerBridge bridge;

    public ShizukuCommandRunner(Availability availability, PackageManagerBridge bridge) {
        this.availability = availability;
        this.bridge = bridge;
    }

    @Override
    public String name() {
        return "Shizuku (ADB-level)";
    }

    @Override
    public Privilege privilege() {
        return Privilege.SHELL;
    }

    @Override
    public boolean isAvailable() {
        return availability.isAvailable();
    }

    @Override
    public String unavailableReason() {
        return availability.reason();
    }

    @Override
    public Result run(String... argv) {
        return run(Arrays.asList(argv));
    }

    @Override
    public Result run(List<String> argv) {
        if (argv.isEmpty()) return new Result(-1, "", "Empty command");
        String head = argv.get(0);
        if (!"pm".equals(head) && !"cmd".equals(head)) {
            return new Result(-1, "",
                    "Shizuku backend supports package operations only (got: " + head + ")");
        }
        try {
            return runPackageCommand(argv);
        } catch (RemoteException e) {
            Log.w(TAG, "remote call failed", e);
            return new Result(-1, "", "Shizuku call failed: " + e.getMessage());
        }
    }

    private Result runPackageCommand(List<String> argv) throws RemoteException {
        if (argv.size() >= 2 && "list".equals(argv.get(1))) {
            return bridgeListPackages(argv.contains("-s"));
        }
        if (argv.size() >= 2) {
            String verb = argv.get(1);
            String pkg = lastNonFlag(argv);
            if (pkg == null) return new Result(-1, "", "Missing package name");
            String outcome;
            switch (verb) {
                case "uninstall":
                    outcome = bridge.uninstall(pkg);
                    break;
                case "disable-user":
                    outcome = bridge.setEnabled(pkg, false);
                    break;
                case "enable":
                    outcome = bridge.setEnabled(pkg, true);
                    break;
                case "install-existing":
                    outcome = bridge.installExisting(pkg);
                    break;
                default:
                    return new Result(-1, "", "Unsupported pm verb: " + verb);
            }
            if (outcome == null) {
                return new Result(0, "Success", "");
            }
            return new Result(1, "", outcome);
        }
        return new Result(-1, "", "Unsupported pm command");
    }

    private Result bridgeListPackages(boolean systemOnly) throws RemoteException {
        StringBuilder sb = new StringBuilder();
        for (String pkg : bridge.listPackages(systemOnly)) {
            sb.append("package:").append(pkg).append('\n');
        }
        return new Result(0, sb.toString(), "");
    }

    private static String lastNonFlag(List<String> argv) {
        for (int i = argv.size() - 1; i >= 0; i--) {
            String value = argv.get(i);
            if (!value.startsWith("-")) return value;
        }
        return null;
    }

    /** Build the argv a caller would use for a package action. */
    public static List<String> pmArgs(String action, String pkg) {
        List<String> args = new ArrayList<>();
        args.add("pm");
        switch (action) {
            case "uninstall":
                args.add("uninstall");
                args.add("-k");
                args.add("--user");
                args.add("0");
                break;
            case "disable":
                args.add("disable-user");
                args.add("--user");
                args.add("0");
                break;
            case "enable":
                args.add("enable");
                break;
            case "restore":
                args.add("install-existing");
                break;
            default:
                throw new IllegalArgumentException("Unknown action: " + action);
        }
        args.add(pkg);
        return args;
    }
}
