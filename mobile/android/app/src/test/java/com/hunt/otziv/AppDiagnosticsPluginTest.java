package com.hunt.otziv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import org.junit.Test;

public class AppDiagnosticsPluginTest {

    @Test
    public void mapsActionableAndroidExitReasonsToStableLogValues() {
        assertEquals("low_memory", AppDiagnosticsPlugin.reasonName(ApplicationExitInfo.REASON_LOW_MEMORY));
        assertEquals("crash", AppDiagnosticsPlugin.reasonName(ApplicationExitInfo.REASON_CRASH));
        assertEquals("crash_native", AppDiagnosticsPlugin.reasonName(ApplicationExitInfo.REASON_CRASH_NATIVE));
        assertEquals("anr", AppDiagnosticsPlugin.reasonName(ApplicationExitInfo.REASON_ANR));
        assertEquals("user_requested", AppDiagnosticsPlugin.reasonName(ApplicationExitInfo.REASON_USER_REQUESTED));
        assertEquals("unknown", AppDiagnosticsPlugin.reasonName(Integer.MAX_VALUE));
    }

    @Test
    public void mapsProcessImportanceToStableLogValues() {
        assertEquals(
                "foreground",
                AppDiagnosticsPlugin.importanceName(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND)
        );
        assertEquals("cached", AppDiagnosticsPlugin.importanceName(ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED));
        assertEquals("other", AppDiagnosticsPlugin.importanceName(Integer.MAX_VALUE));
    }

    @Test
    public void acceptsOnlyOtzivVersionedProcessStateSummaries() {
        assertTrue(AppDiagnosticsPlugin.isOtzivProcessStateSummary(
                "v1;route=/tabs/home;state=background;action=navigation.changed;build=69"
        ));
        assertFalse(AppDiagnosticsPlugin.isOtzivProcessStateSummary(
                "150.0.7871.181 chromium-state\u0000\u0003"
        ));
        assertFalse(AppDiagnosticsPlugin.isOtzivProcessStateSummary("route=/tabs/home;state=background"));
    }
}
