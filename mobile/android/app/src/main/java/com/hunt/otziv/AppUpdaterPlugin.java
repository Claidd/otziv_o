package com.hunt.otziv;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import androidx.core.content.FileProvider;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

@CapacitorPlugin(name = "AppUpdater")
public class AppUpdaterPlugin extends Plugin {

    private static final String PREFS_NAME = "otziv_app_updater";
    private static final String KEY_DOWNLOAD_ID = "download_id";
    private static final String KEY_FILE_NAME = "file_name";
    private static final String KEY_SHA256 = "sha256";
    private static final String APK_MIME_TYPE = "application/vnd.android.package-archive";

    @PluginMethod
    public void getInfo(PluginCall call) {
        try {
            PackageInfo info = installedPackageInfo();
            JSObject result = new JSObject();
            result.put("versionName", info.versionName == null ? "" : info.versionName);
            result.put("versionCode", versionCode(info));
            result.put("canInstallPackages", canInstallPackages());
            call.resolve(result);
        } catch (Exception exception) {
            call.reject("Не удалось определить версию приложения.", exception);
        }
    }

    @PluginMethod
    public void openInstallPermission(PluginCall call) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || canInstallPackages()) {
            call.resolve();
            return;
        }

        try {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getContext().getPackageName())
            );
            getActivity().startActivity(intent);
            call.resolve();
        } catch (Exception exception) {
            call.reject("Не удалось открыть разрешение на установку приложений.", exception);
        }
    }

    @PluginMethod
    public void startDownload(PluginCall call) {
        String url = call.getString("url", "").trim();
        String sha256 = call.getString("sha256", "").trim().toUpperCase(Locale.ROOT);
        String versionName = safeVersionName(call.getString("versionName", "update"));
        if (!isHttpUrl(url)) {
            call.reject("Некорректная ссылка обновления.");
            return;
        }
        if (!sha256.matches("[0-9A-F]{64}")) {
            call.reject("Некорректная контрольная сумма обновления.");
            return;
        }

        try {
            DownloadManager manager = downloadManager();
            SharedPreferences preferences = preferences();
            long previousId = preferences.getLong(KEY_DOWNLOAD_ID, -1L);
            if (previousId >= 0) {
                manager.remove(previousId);
            }

            String fileName = "otziv-update-" + versionName + ".apk";
            File target = updateFile(fileName);
            if (target.exists() && !target.delete()) {
                call.reject("Не удалось удалить предыдущий файл обновления.");
                return;
            }

            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url))
                    .setTitle("Компания О! " + versionName)
                    .setDescription("Загрузка обновления приложения")
                    .setMimeType(APK_MIME_TYPE)
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(false)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalFilesDir(getContext(), Environment.DIRECTORY_DOWNLOADS, fileName);

            long downloadId = manager.enqueue(request);
            preferences.edit()
                    .putLong(KEY_DOWNLOAD_ID, downloadId)
                    .putString(KEY_FILE_NAME, fileName)
                    .putString(KEY_SHA256, sha256)
                    .apply();

            JSObject result = new JSObject();
            result.put("downloadId", downloadId);
            call.resolve(result);
        } catch (Exception exception) {
            call.reject("Не удалось начать загрузку обновления.", exception);
        }
    }

    @PluginMethod
    public void getDownloadStatus(PluginCall call) {
        long downloadId = preferences().getLong(KEY_DOWNLOAD_ID, -1L);
        JSObject result = new JSObject();
        if (downloadId < 0) {
            result.put("state", "idle");
            result.put("progress", 0);
            call.resolve(result);
            return;
        }

        DownloadManager.Query query = new DownloadManager.Query().setFilterById(downloadId);
        try (android.database.Cursor cursor = downloadManager().query(query)) {
            if (!cursor.moveToFirst()) {
                clearDownloadState();
                result.put("state", "idle");
                result.put("progress", 0);
                call.resolve(result);
                return;
            }

            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            long downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            long total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            result.put("state", downloadState(status));
            result.put("downloadedBytes", Math.max(downloaded, 0));
            result.put("totalBytes", Math.max(total, 0));
            result.put("progress", total > 0 ? Math.min(100, Math.round(downloaded * 100.0 / total)) : 0);
            if (status == DownloadManager.STATUS_FAILED || status == DownloadManager.STATUS_PAUSED) {
                result.put("reason", cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)));
            }
            call.resolve(result);
        } catch (Exception exception) {
            call.reject("Не удалось проверить загрузку обновления.", exception);
        }
    }

    @PluginMethod
    public void installDownloaded(PluginCall call) {
        execute(() -> {
            try {
                SharedPreferences preferences = preferences();
                String fileName = preferences.getString(KEY_FILE_NAME, "");
                String expectedSha256 = preferences.getString(KEY_SHA256, "");
                File apk = updateFile(fileName);
                if (!apk.isFile()) {
                    throw new IllegalStateException("Загруженный APK не найден.");
                }
                String actualSha256 = sha256(apk);
                if (!actualSha256.equalsIgnoreCase(expectedSha256)) {
                    apk.delete();
                    clearDownloadState();
                    throw new SecurityException("Контрольная сумма APK не совпадает.");
                }

                validatePackageAndSignature(apk);
                getActivity().runOnUiThread(() -> {
                    try {
                        Uri uri = FileProvider.getUriForFile(
                                getContext(),
                                getContext().getPackageName() + ".fileprovider",
                                apk
                        );
                        Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE)
                                .setData(uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK)
                                .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                                .putExtra(Intent.EXTRA_RETURN_RESULT, false);
                        getActivity().startActivity(intent);
                        call.resolve();
                    } catch (Exception exception) {
                        call.reject("Не удалось открыть установщик Android.", exception);
                    }
                });
            } catch (Exception exception) {
                call.reject(exception.getMessage() == null ? "Проверка APK завершилась ошибкой." : exception.getMessage(), exception);
            }
        });
    }

    private void validatePackageAndSignature(File apk) throws Exception {
        PackageManager packageManager = getContext().getPackageManager();
        PackageInfo archive = packageManager.getPackageArchiveInfo(apk.getAbsolutePath(), packageInfoFlags());
        PackageInfo installed = installedPackageInfo();
        if (archive == null || !getContext().getPackageName().equals(archive.packageName)) {
            throw new SecurityException("APK относится к другому приложению.");
        }
        if (versionCode(archive) <= versionCode(installed)) {
            throw new SecurityException("Версия APK не новее установленной.");
        }
        if (!signatureDigests(archive).equals(signatureDigests(installed))) {
            throw new SecurityException("Подпись APK не совпадает с установленным приложением.");
        }
    }

    private Set<String> signatureDigests(PackageInfo info) throws Exception {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
            signatures = info.signingInfo.hasMultipleSigners()
                    ? info.signingInfo.getApkContentsSigners()
                    : info.signingInfo.getSigningCertificateHistory();
        } else {
            signatures = info.signatures;
        }
        Set<String> digests = new HashSet<>();
        if (signatures != null) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Signature signature : signatures) {
                digests.add(hex(digest.digest(signature.toByteArray())));
            }
        }
        if (digests.isEmpty()) {
            throw new SecurityException("Не удалось проверить подпись APK.");
        }
        return digests;
    }

    private PackageInfo installedPackageInfo() throws PackageManager.NameNotFoundException {
        return getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), packageInfoFlags());
    }

    private int packageInfoFlags() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES;
    }

    private long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? info.getLongVersionCode() : info.versionCode;
    }

    private boolean canInstallPackages() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || getContext().getPackageManager().canRequestPackageInstalls();
    }

    private DownloadManager downloadManager() {
        return (DownloadManager) getContext().getSystemService(Context.DOWNLOAD_SERVICE);
    }

    private SharedPreferences preferences() {
        return getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private File updateFile(String fileName) {
        File directory = getContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (directory == null) {
            throw new IllegalStateException("Хранилище обновлений недоступно.");
        }
        if (fileName == null || !fileName.matches("[0-9A-Za-z._-]+\\.apk")) {
            throw new IllegalStateException("Некорректное имя APK.");
        }
        return new File(directory, fileName);
    }

    private void clearDownloadState() {
        preferences().edit().clear().apply();
    }

    private String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = new FileInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return hex(digest.digest());
    }

    private String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format(Locale.ROOT, "%02X", value));
        }
        return builder.toString();
    }

    private boolean isHttpUrl(String value) {
        Uri uri = Uri.parse(value);
        return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null;
    }

    private String safeVersionName(String value) {
        String normalized = value == null ? "update" : value.replaceAll("[^0-9A-Za-z._-]", "-");
        return normalized.isBlank() ? "update" : normalized;
    }

    private String downloadState(int status) {
        return switch (status) {
            case DownloadManager.STATUS_PENDING -> "pending";
            case DownloadManager.STATUS_RUNNING -> "running";
            case DownloadManager.STATUS_PAUSED -> "paused";
            case DownloadManager.STATUS_SUCCESSFUL -> "successful";
            case DownloadManager.STATUS_FAILED -> "failed";
            default -> "unknown";
        };
    }
}
