package com.hunt.otziv;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import static org.junit.Assert.*;

public class AuthStorageDurabilityPluginTest {
    @Test public void onlyPinnedStoreSelectorsAreAccepted() {
        assertEquals("CapacitorStorage", AuthStorageDurabilityPlugin.storeName("preferences"));
        assertEquals("WSSecureStorageSharedPreferences", AuthStorageDurabilityPlugin.storeName("secure"));
        for (String untrusted : new String[] { null, "", "../auth", "CapacitorStorage", "/data/local/tmp", "secure " })
            assertThrows(IllegalArgumentException.class, () -> AuthStorageDurabilityPlugin.storeName(untrusted));
    }

    @Test public void failedDiskCommitNeverBecomesSuccess() {
        assertThrows(IllegalStateException.class, () -> AuthStorageDurabilityPlugin.requireCommitted(() -> false));
        AuthStorageDurabilityPlugin.requireCommitted(() -> true);
    }

    @Test public void interruptionRejectsBeforeWritingAndIsPreserved() {
        AtomicBoolean invoked = new AtomicBoolean(); Thread.currentThread().interrupt();
        try {
            assertThrows(IllegalStateException.class, () -> AuthStorageDurabilityPlugin.requireCommitted(() -> { invoked.set(true); return true; }));
            assertFalse(invoked.get()); assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test public void interruptionDuringCommitCannotResolveAsSuccess() {
        try {
            assertThrows(IllegalStateException.class, () -> AuthStorageDurabilityPlugin.requireCommitted(() -> { Thread.currentThread().interrupt(); return true; }));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
}
