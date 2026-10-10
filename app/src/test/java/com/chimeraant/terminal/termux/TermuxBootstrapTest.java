package com.chimeraant.terminal.termux;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The Termux prefix path is compiled into the Termux binaries, so the proot
 * bind that maps it must stay in sync. These tests pin that contract.
 */
public class TermuxBootstrapTest {

    private static final String PREFIX = "/data/data/com.termux/files/usr";
    private static final String HOME = "/data/data/com.termux/files/home";

    @Test
    public void assetNamesMatchTermuxConvention() {
        String name = TermuxBootstrapInstaller.bootstrapAssetName();
        assertTrue("unexpected asset name: " + name, name.startsWith("bootstrap-"));
        assertTrue(name.endsWith(".zip"));
        String arch = name.substring("bootstrap-".length(), name.length() - 4);
        assertTrue("unknown arch: " + arch,
                arch.equals("aarch64") || arch.equals("arm")
                        || arch.equals("x86_64") || arch.equals("i686"));
    }

    @Test
    public void releaseUrlPointsAtThePinnedTag() {
        assertTrue(TermuxBootstrapInstaller.BOOTSTRAP_BASE.contains("termux-packages"));
        assertTrue(TermuxBootstrapInstaller.BOOTSTRAP_BASE.contains("releases/download"));
        assertTrue(TermuxBootstrapInstaller.BOOTSTRAP_BASE
                .contains(TermuxBootstrapInstaller.BOOTSTRAP_TAG));
    }

    @Test
    public void prefixIsTheCompiledInTermuxPath() {
        assertEquals("/data/data/com.termux/files/usr", PREFIX);
        assertEquals("/data/data/com.termux/files/home", HOME);
    }

    @Test
    public void loginShapeBindsTheHardcodedPrefix() {
        // proot must map our real prefix onto the path the binaries expect,
        // otherwise every Termux program fails to find its own libraries.
        String[] argv = {
                "/proot",
                "--link2symlink",
                "-r", "/rootfs",
                "-b", "/dev",
                "-b", "/real/usr" + ":" + PREFIX,
                "-b", "/real/home" + ":" + HOME,
                "-w", "/real/home",
                "/usr/bin/env", "-i",
                "PREFIX=" + PREFIX,
                PREFIX + "/bin/login"
        };
        boolean hasPrefixBind = false;
        boolean hasHomeBind = false;
        for (String arg : argv) {
            if (arg.equals("/real/usr:" + PREFIX)) hasPrefixBind = true;
            if (arg.equals("/real/home:" + HOME)) hasHomeBind = true;
        }
        assertTrue("login command must bind the Termux prefix", hasPrefixBind);
        assertTrue("login command must bind the Termux home", hasHomeBind);
    }
}
