package com.hunt.otziv;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@CapacitorPlugin(name = "AppDiagnostics")
public class AppDiagnosticsPlugin extends Plugin {

    private static final String PREFS_NAME = "otziv_app_diagnostics";
    private static final String KEY_PROCESS_STATE = "process_state";
    private static final String KEY_PROCESS_STATE_UPDATED_AT = "process_state_updated_at";
    private static final String KEY_ACKNOWLEDGED_EXIT_TIMESTAMP = "acknowledged_exit_timestamp";
    private static final int MAX_EXIT_RECORDS = 8;
    private static final int MAX_PROCESS_STATE_BYTES = 128;
    private static final int MAX_DESCRIPTION_LENGTH = 160;

    private String startupProcessState = "";
    private long startupProcessStateUpdatedAt;

    @Override
    public void load() {
        SharedPreferences preferences = preferences();
        startupProcessState = safeText(preferences.getString(KEY_PROCESS_STATE, ""), MAX_PROCESS_STATE_BYTES);
        startupProcessStateUpdatedAt = preferences.getLong(KEY_PROCESS_STATE_UPDATED_AT, 0L);
    }

    @PluginMethod
    public void getPreviousExits(PluginCall call) {
        JSObject result = new JSObject();
        result.put("androidApi", Build.VERSION.SDK_INT);
        result.put("supported", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R);
        result.put("previousStateSummary", startupProcessState);
        result.put("previousStateUpdatedAt", startupProcessStateUpdatedAt);

        JSArray exits = new JSArray();
        result.put("exits", exits);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            result.put("lowMemoryKillReportSupported", false);
            call.resolve(result);
            return;
        }

        try {
            ActivityManager manager = activityManager();
            result.put("lowMemoryKillReportSupported", ActivityManager.isLowMemoryKillReportSupported());
            long acknowledgedTimestamp = preferences().getLong(KEY_ACKNOWLEDGED_EXIT_TIMESTAMP, 0L);
            List<ApplicationExitInfo> candidates = new ArrayList<>(manager.getHistoricalProcessExitReasons(
                    getContext().getPackageName(),
                    0,
                    MAX_EXIT_RECORDS
            ));
            candidates.sort(Comparator.comparingLong(ApplicationExitInfo::getTimestamp));

            List<ApplicationExitInfo> reportable = new ArrayList<>();
            for (ApplicationExitInfo exit : candidates) {
                if (exit.getTimestamp() <= acknowledgedTimestamp || !isMainProcess(exit)) {
                    continue;
                }
                reportable.add(exit);
            }
            for (int index = 0; index < reportable.size(); index++) {
                exits.put(exitJson(reportable.get(index), index == reportable.size() - 1));
            }
            call.resolve(result);
        } catch (Exception exception) {
            call.reject("Не удалось прочитать причину завершения Android-процесса.", exception);
        }
    }

    @PluginMethod
    public void acknowledgePreviousExits(PluginCall call) {
        long timestamp = Math.max(0L, call.getLong("throughTimestamp", 0L));
        SharedPreferences preferences = preferences();
        long current = preferences.getLong(KEY_ACKNOWLEDGED_EXIT_TIMESTAMP, 0L);
        if (timestamp > current) {
            preferences.edit().putLong(KEY_ACKNOWLEDGED_EXIT_TIMESTAMP, timestamp).apply();
        }
        call.resolve();
    }

    @PluginMethod
    public void setProcessStateSummary(PluginCall call) {
        String summary = safeUtf8(call.getString("summary", ""), MAX_PROCESS_STATE_BYTES);
        long updatedAt = System.currentTimeMillis();
        preferences().edit()
                .putString(KEY_PROCESS_STATE, summary)
                .putLong(KEY_PROCESS_STATE_UPDATED_AT, updatedAt)
                .apply();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                activityManager().setProcessStateSummary(summary.getBytes(StandardCharsets.UTF_8));
            } catch (RuntimeException ignored) {
                // Android may throttle excessive state updates. The local fallback remains available.
            }
        }
        call.resolve();
    }

    private JSObject exitJson(ApplicationExitInfo exit, boolean allowLocalStateFallback) {
        JSObject result = new JSObject();
        result.put("timestamp", exit.getTimestamp());
        result.put("reason", reasonName(exit.getReason()));
        result.put("reasonCode", exit.getReason());
        result.put("status", exit.getStatus());
        result.put("importance", importanceName(exit.getImportance()));
        result.put("importanceCode", exit.getImportance());
        result.put("pssKb", Math.max(0L, exit.getPss()));
        result.put("rssKb", Math.max(0L, exit.getRss()));
        result.put("description", safeText(exit.getDescription(), MAX_DESCRIPTION_LENGTH));

        byte[] processState = exit.getProcessStateSummary();
        String androidStateSummary = processState == null
                ? ""
                : new String(processState, StandardCharsets.UTF_8);
        boolean androidStateSummaryRejected = !androidStateSummary.isBlank()
                && !isOtzivProcessStateSummary(androidStateSummary);
        result.put("androidStateSummaryRejected", androidStateSummaryRejected);
        if (isOtzivProcessStateSummary(androidStateSummary)) {
            result.put("stateSummary", safeText(androidStateSummary, MAX_PROCESS_STATE_BYTES));
            result.put("stateSource", "android_exit_info");
        } else if (allowLocalStateFallback && isOtzivProcessStateSummary(startupProcessState)) {
            result.put("stateSummary", startupProcessState);
            result.put("stateSource", "local_fallback");
        } else {
            result.put("stateSummary", "");
            result.put("stateSource", "none");
        }
        return result;
    }

    private boolean isMainProcess(ApplicationExitInfo exit) {
        String processName = exit.getProcessName();
        return processName == null || processName.equals(getContext().getPackageName());
    }

    private ActivityManager activityManager() {
        return (ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
    }

    private SharedPreferences preferences() {
        return getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private String safeUtf8(String value, int maxBytes) {
        String normalized = safeText(value, maxBytes * 2);
        byte[] bytes = normalized.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return normalized;
        }
        int length = maxBytes;
        while (length > 0 && (bytes[length] & 0xC0) == 0x80) {
            length--;
        }
        return new String(bytes, 0, Math.max(0, length), StandardCharsets.UTF_8);
    }

    private String safeText(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("[\\p{Cntrl}]", " ").trim();
        return normalized.substring(0, Math.min(normalized.length(), maxLength));
    }

    static boolean isOtzivProcessStateSummary(String value) {
        if (value == null
                || !value.startsWith("v1;route=")
                || !value.contains(";state=")
                || !value.contains(";action=")) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean safe = character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9'
                    || character == '_'
                    || character == '.'
                    || character == '/'
                    || character == ':'
                    || character == '-'
                    || character == ';'
                    || character == '=';
            if (!safe) {
                return false;
            }
        }
        return true;
    }

    static String reasonName(int reason) {
        return switch (reason) {
            case ApplicationExitInfo.REASON_EXIT_SELF -> "exit_self";
            case ApplicationExitInfo.REASON_SIGNALED -> "signaled";
            case ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory";
            case ApplicationExitInfo.REASON_CRASH -> "crash";
            case ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash_native";
            case ApplicationExitInfo.REASON_ANR -> "anr";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization_failure";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission_change";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive_resource_usage";
            case ApplicationExitInfo.REASON_USER_REQUESTED -> "user_requested";
            case ApplicationExitInfo.REASON_USER_STOPPED -> "user_stopped";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency_died";
            case ApplicationExitInfo.REASON_OTHER -> "other";
            case ApplicationExitInfo.REASON_FREEZER -> "freezer";
            case ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "package_state_change";
            case ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "package_updated";
            default -> "unknown";
        };
    }

    static String importanceName(int importance) {
        return switch (importance) {
            case ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "foreground";
            case ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "foreground_service";
            case ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible";
            case ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "perceptible";
            case ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "service";
            case ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "cached";
            case ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "gone";
            default -> "other";
        };
    }
}
