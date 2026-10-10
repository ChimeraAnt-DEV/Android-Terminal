package com.chimeraant.terminal.debloat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The safety policy is what stops a user bricking their tablet. */
public class DebloatSafetyTest {

    @Test
    public void blocksCoreSystemComponents() {
        assertEquals(DebloatSafety.Risk.CRITICAL,
                DebloatSafety.classify("com.android.systemui"));
        assertEquals(DebloatSafety.Risk.CRITICAL,
                DebloatSafety.classify("com.android.settings"));
        assertEquals(DebloatSafety.Risk.CRITICAL,
                DebloatSafety.classify("android"));
        assertEquals(DebloatSafety.Risk.CRITICAL,
                DebloatSafety.classify("com.google.android.gms"));
    }

    @Test
    public void blocksFireOsEssentials() {
        // Removing any of these on a Fire tablet breaks boot or the launcher.
        assertEquals(DebloatSafety.Risk.CRITICAL,
                DebloatSafety.classify("com.amazon.device.software.ota"));
        assertEquals(DebloatSafety.Risk.CRITICAL,
                DebloatSafety.classify("com.amazon.firelauncher"));
        assertEquals(DebloatSafety.Risk.CRITICAL,
                DebloatSafety.classify("com.amazon.device.settings"));
    }

    @Test
    public void flagsOemAppsAsCaution() {
        assertEquals(DebloatSafety.Risk.CAUTION,
                DebloatSafety.classify("com.amazon.avod"));
        assertEquals(DebloatSafety.Risk.CAUTION,
                DebloatSafety.classify("com.samsung.android.bixby"));
        assertEquals(DebloatSafety.Risk.CAUTION,
                DebloatSafety.classify("com.google.android.apps.docs"));
    }

    @Test
    public void allowsOrdinaryThirdPartyApps() {
        assertEquals(DebloatSafety.Risk.SAFE,
                DebloatSafety.classify("com.example.game"));
        assertEquals(DebloatSafety.Risk.SAFE,
                DebloatSafety.classify("org.telegram.messenger"));
    }

    @Test
    public void protectedPackagesAreNotBulkSafe() {
        assertTrue(DebloatSafety.isProtected("com.android.systemui"));
        assertFalse(DebloatSafety.isBulkSafe("com.android.systemui"));
        assertTrue(DebloatSafety.isBulkSafe("com.example.game"));
    }

    @Test
    public void explainsWhySomethingIsBlocked() {
        String message = DebloatSafety.explain("com.android.systemui");
        assertTrue(message.contains("core system"));
        assertTrue(message.contains("boot"));
    }
}
