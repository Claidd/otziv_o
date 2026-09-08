package com.hunt.otziv;

import android.annotation.SuppressLint;
import android.content.Context;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Disk barrier for the two pinned auth stores; callers serialize mutation -> flush pairs. */
@CapacitorPlugin(name = "AuthStorageDurability")
public class AuthStorageDurabilityPlugin extends Plugin {
    private final ThreadPoolExecutor commits = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(8), runnable -> new Thread(runnable, "otziv-auth-storage-commit"),
            new ThreadPoolExecutor.AbortPolicy());

    @PluginMethod
    public void flush(PluginCall call) {
        final String preferences;
        try { preferences = storeName(call.getString("store")); }
        catch (IllegalArgumentException invalid) { call.reject("Unsupported auth storage", "INVALID_AUTH_STORE"); return; }
        try {
            commits.execute(() -> {
                try {
                    requireCommitted(() -> commit(preferences));
                    call.resolve();
                } catch (RuntimeException failed) {
                    // No values, file contents, credentials or exception details cross the bridge.
                    call.reject("Auth storage could not be committed", "AUTH_STORAGE_COMMIT_FAILED");
                }
            });
        } catch (RejectedExecutionException unavailable) {
            call.reject("Auth storage commit unavailable", "AUTH_STORAGE_COMMIT_UNAVAILABLE");
        }
    }

    @SuppressLint("ApplySharedPref") // An awaited apply() is not durable. This runs off the UI thread.
    private boolean commit(String preferences) {
        // Android's Editor contract: commit waits for outstanding apply writes on the same store.
        // A no-op editor commits the current memory generation without changing any auth value.
        return getContext().getSharedPreferences(preferences, Context.MODE_PRIVATE).edit().commit();
    }

    static String storeName(String store) {
        if ("preferences".equals(store)) return "CapacitorStorage";
        if ("secure".equals(store)) return "WSSecureStorageSharedPreferences";
        throw new IllegalArgumentException("Unsupported auth storage");
    }

    static void requireCommitted(BooleanSupplier operation) {
        if (Thread.currentThread().isInterrupted() || !operation.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new IllegalStateException("Auth storage commit did not complete");
    }

    @Override
    protected void handleOnDestroy() {
        commits.shutdown();
        super.handleOnDestroy();
    }
}
