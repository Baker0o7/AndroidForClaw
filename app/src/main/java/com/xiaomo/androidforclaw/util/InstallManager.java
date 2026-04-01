/**
 * OpenClaw Source Reference:
 * - No OpenClaw equivalent (Android platform specific)
 */
package com.xiaomo.androidforclaw.util;

import static android.content.pm.PackageInstaller.EXTRA_PACKAGE_NAME;
import static android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;

import com.xiaomo.androidforclaw.core.MyApplication;
import com.xiaomo.androidforclaw.data.model.ApkInfo;
import com.xiaomo.androidforclaw.util.WakeLockManager;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class InstallManager {

    private static final String TAG = "InstallManager";
    public static final String PACKAGE_NAME = "packageName";
    public static final Map<String, IPackageInstallObserver> sObservers = Collections.synchronizedMap(new HashMap<>());
    private static final InstallManager instance = new InstallManager();

    private InstallManager() {
    }

    public static InstallManager getInstance() {
        return instance;
    }

    public static void apkInstall(String apkAbsolutePath, PackageInstallObserver observer) {
        Log.d(TAG, "Starting APK installation, path: " + apkAbsolutePath);

        if (TextUtils.isEmpty(apkAbsolutePath)) {
            Log.e(TAG, "APK path is empty!");
            observer.onInstallFailure("", "APKABSOLUTEPATH_IS_NULL");
            return;
        }

        File apkFile = new File(apkAbsolutePath);
        if (!apkFile.exists()) {
            Log.e(TAG, "APK file does not exist: " + apkAbsolutePath);
            observer.onInstallFailure(apkAbsolutePath, "APK_FILE_NOT_EXISTS");
            return;
        }

        Log.d(TAG, "APK file exists, size: " + apkFile.length() + " bytes");
        observer.onInstallStart(apkAbsolutePath);

        // Check install permission
        Context context = MyApplication.application.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            boolean hasInstallPermission = context.getPackageManager().canRequestPackageInstalls();
            Log.d(TAG, "Install permission check: " + hasInstallPermission);
            if (!hasInstallPermission) {
                Log.w(TAG, "No install permission, silent installation may fail");
                observer.onInstallFailure(apkAbsolutePath, "NO_INSTALL_PERMISSION");
                return;
            }
        }

        // Check storage permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            boolean hasStoragePermission = android.os.Environment.isExternalStorageManager();
            Log.d(TAG, "Storage manager permission check: " + hasStoragePermission);
            if (!hasStoragePermission) {
                Log.w(TAG, "No storage manager permission, may not access external storage");
            }
        }

        Log.d(TAG, "Starting APK file parsing: " + apkAbsolutePath);
        Log.d(TAG, "APK file readability: " + apkFile.canRead());
        Log.d(TAG, "APK file permissions: " + apkFile.getAbsolutePath());

        ApkInfo apkInfo = new ApkInfo(Uri.fromFile(apkFile));
        Log.d(TAG, "ApkInfo object created successfully");

        boolean prepareResult = apkInfo.prepare();
        Log.d(TAG, "APK parse result: " + prepareResult);

        if (!prepareResult) {
            Log.e(TAG, "APK file parsing failed, may not be a valid APK file");
            Log.e(TAG, "APK file path: " + apkAbsolutePath);
            Log.e(TAG, "APK file size: " + apkFile.length());
            Log.e(TAG, "APK file exists: " + apkFile.exists());
            Log.e(TAG, "APK file readable: " + apkFile.canRead());
            observer.onInstallFailure(apkAbsolutePath, "APK_UNAVAILABLE");
            return;
        }

        Log.d(TAG, "APK info parsed successfully, package name: " + apkInfo.getApkPackageName());

        // Directly call install method
        boolean installResult = installApkDirectly(apkInfo, observer);
        if (!installResult) {
            Log.e(TAG, "APK installation failed");
            observer.onInstallFailure(apkAbsolutePath, "INSTALL_FAILED");
        }
    }

    private static boolean installApkDirectly(ApkInfo apkInfo, PackageInstallObserver observer) {
        Log.d(TAG, "Starting direct APK installation: " + apkInfo.getApkPackageName());

        InputStream in = null;
        OutputStream out = null;
        PackageInstaller.Session session = null;
        String packageName = apkInfo.getApkPackageName();

        // Set up timeout handling
        Handler handler = new Handler(android.os.Looper.getMainLooper());
        Runnable timeoutRunnable = new Runnable() {
            @Override
            public void run() {
                if (sObservers.containsKey(packageName)) {
                    Log.e(TAG, "APK installation timeout: " + packageName);
                    observer.onInstallFailure(apkInfo.getApkUri().getPath(), "INSTALL_TIMEOUT");
                    sObservers.remove(packageName);
                }
            }
        };

        // Create install result listener
        InstallResultListener resultListener = new InstallResultListener(packageName, observer);
        resultListener.setHandler(handler, timeoutRunnable);
        sObservers.put(packageName, resultListener);

        handler.postDelayed(timeoutRunnable, 60000); // 60 second timeout

        try {
            Context context = MyApplication.application.getApplicationContext();
            PackageInstaller packageInstaller = context.getPackageManager().getPackageInstaller();

            Log.d(TAG, "Creating install session...");
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(packageName);

            int sessionId = packageInstaller.createSession(params);
            Log.d(TAG, "Install session created, ID: " + sessionId);

            session = packageInstaller.openSession(sessionId);
            Uri uri = apkInfo.getApkUri();
            File apkFile = new File(uri.getPath());

            Log.d(TAG, "Writing APK data...");
            in = new FileInputStream(apkFile);
            String sessionName = String.valueOf(Math.abs(uri.getPath().hashCode()));
            out = session.openWrite(sessionName, 0, apkFile.length());

            int read;
            byte[] buffer = new byte[65536];
            long totalBytes = 0;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                totalBytes += read;
                if (totalBytes % (1024 * 1024) == 0) { // Print progress every 1MB
                    Log.d(TAG, "Written: " + (totalBytes / 1024 / 1024) + "MB");
                }
            }

            Log.d(TAG, "APK data written, total: " + totalBytes + " bytes");
            session.fsync(out);
            in.close();
            out.close();

            Log.d(TAG, "Committing install session...");
            IntentSender intentSender = getDefaultIntentSender(context, packageName);
            Log.d(TAG, "IntentSender created: " + intentSender);
            session.commit(intentSender);
            Log.d(TAG, "APK install committed, checking install status...");

            // Use delayed check approach, not relying on broadcast receiver
            checkInstallStatusDelayed(packageName, observer, handler, timeoutRunnable);

            // Also start system install Intent as fallback
//            startSystemInstallIntent(apkInfo, observer);
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Exception during APK installation: " + e.getMessage(), e);
            observer.onInstallFailure(apkInfo.getApkUri().getPath(), "INSTALL_EXCEPTION: " + e.getMessage());
            sObservers.remove(packageName);
            handler.removeCallbacks(timeoutRunnable);
            return false;
        } finally {
            closeQuietly(session);
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    private static void checkInstallStatusDelayed(String packageName, PackageInstallObserver observer,
                                                  Handler handler, Runnable timeoutRunnable) {
        Log.d(TAG, "Starting delayed install status check: " + packageName);

        // Check install status every 2 seconds, max 15 checks (30 seconds)
        final int[] checkCount = {0};
        final int maxChecks = 15;

        Runnable checkRunnable = new Runnable() {
            @Override
            public void run() {
                checkCount[0]++;
                Log.d(TAG, "Install status check #" + checkCount[0] + ": " + packageName);

                try {
                    Context context = MyApplication.application.getApplicationContext();
                    PackageManager pm = context.getPackageManager();

                    try {
                        PackageInfo packageInfo = pm.getPackageInfo(packageName, 0);
                        if (packageInfo.applicationInfo.enabled) {
                            long installTime = packageInfo.firstInstallTime;
                            long lastUpdateTime = packageInfo.lastUpdateTime;
                            long currentTime = System.currentTimeMillis();
                            // If installed or updated within the last minute, consider it a success
                            if (currentTime - installTime < 60000 || currentTime - lastUpdateTime < 60000) {
                                Intent launchIntent = pm.getLaunchIntentForPackage(packageName);
                                if (launchIntent != null) {
                                    Log.d(TAG, "APK installed successfully: " + packageName);
                                    handler.removeCallbacks(timeoutRunnable);
                                    observer.onInstallSuccess("Installation successful");
                                    sObservers.remove(packageName);
                                } else {
                                    Log.d(TAG, "App installed but no launch Activity, continuing to wait...");
                                }
                            } else {
                                Log.d(TAG, "App already exists but not newly installed, continuing to wait...");
                            }
                        } else {
                            Log.d(TAG, "App installed but disabled, continuing to wait...");
                        }
                    } catch (PackageManager.NameNotFoundException e) {
                        Log.d(TAG, "App not yet installed, continuing to wait...");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Exception while checking install status: " + e.getMessage(), e);
                    handler.removeCallbacks(timeoutRunnable);
                    observer.onInstallFailure("", "INSTALL_CHECK_EXCEPTION: " + e.getMessage());
                    sObservers.remove(packageName);
                }
            }
        };

        // Delay first check by 2 seconds
        handler.postDelayed(checkRunnable, 2000);
    }

    private static void startSystemInstallIntent(ApkInfo apkInfo, PackageInstallObserver observer) {
        try {
            Log.d(TAG, "Launching system install Intent as fallback");
            Context context = MyApplication.application.getApplicationContext();

            // Check install permission
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                boolean hasInstallPermission = context.getPackageManager().canRequestPackageInstalls();
                if (!hasInstallPermission) {
                    Log.w(TAG, "No install permission, cannot use system install Intent");
                    return;
                }
            }

            // Create URI using FileProvider
            Uri apkUri = apkInfo.getApkUri();
            Log.d(TAG, "Original APK URI: " + apkUri);

            // If it's a file:// URI, need to convert to FileProvider URI
            if (apkUri.getScheme().equals("file")) {
                String filePath = apkUri.getPath();
                Log.d(TAG, "APK file path: " + filePath);

                // Create URI using FileProvider
                String authority = context.getPackageName() + ".provider";
                apkUri = androidx.core.content.FileProvider.getUriForFile(
                        context,
                        authority,
                        new java.io.File(filePath)
                );
                Log.d(TAG, "FileProvider URI: " + apkUri);
            }

            // Create system install Intent
            Intent installIntent = new Intent(Intent.ACTION_VIEW);
            installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            installIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);

            Log.d(TAG, "Launching system install Intent: " + installIntent);
            context.startActivity(installIntent);
            Log.d(TAG, "System install Intent launched, user must manually confirm installation");

        } catch (Exception e) {
            Log.e(TAG, "Failed to launch system install Intent: " + e.getMessage(), e);
        }
    }

    private static IntentSender getDefaultIntentSender(Context context, String pkgName) {
        Intent intent = new Intent(InstallResultBroadcastReceiver.ACTION_INSTALL_RESULT);
        intent.setPackage(MyApplication.application.getPackageName());
        int index = 0;
        if (!TextUtils.isEmpty(pkgName)) {
            intent.putExtra(PACKAGE_NAME, pkgName);
            try {
                index = pkgName.hashCode();
            } catch (NumberFormatException e) {
                // Ignore exception
            }
        }
        int flags = PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent
                .getBroadcast(context, index, intent, flags);
        return pendingIntent.getIntentSender();
    }

    public static void notifyResult(final String pkgName, final int returnCode, Bundle extrac) {
        Log.d(TAG, "notifyResult called, package: " + pkgName + ", return code: " + returnCode);
        Log.d(TAG, "Current observer count: " + sObservers.size());
        Log.d(TAG, "Observer list: " + sObservers.keySet());

        IPackageInstallObserver observer = sObservers.remove(pkgName);
        if (observer == null) {
            Log.w(TAG, "No matching observer found: " + pkgName);
            return;
        }

        Log.d(TAG, "Observer found, notifying result");
        try {
            observer.packageInstalledResult(pkgName, returnCode, extrac);
            Log.d(TAG, "Observer notified successfully");
        } catch (Throwable e) {
            Log.e(TAG, "Observer notification failed: " + e.getMessage(), e);
            try {
                observer.reNotifyResultOnError(pkgName, returnCode, extrac);
                Log.d(TAG, "Retry notification succeeded");
            } catch (Throwable ex) {
                Log.e(TAG, "Retry notification also failed: " + ex.getMessage(), ex);
            }
        }
    }

    public static void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception e) {
                // Ignore exception
            }
        }
    }

    public interface IPackageInstallObserver {

        void packageInstalledResult(java.lang.String packageName, int returnCode, android.os.Bundle extras);

        void reNotifyResultOnError(String packageName, int returnCode, Bundle extras);

    }

    public static class MarketInstallObserverDelegate implements IPackageInstallObserver {

        private final ApkInfo mApkInfo;
        private final PackageInstallObserver mDelegate;

        MarketInstallObserverDelegate(ApkInfo apkInfo, PackageInstallObserver listener) {
            mApkInfo = apkInfo;
            mDelegate = listener;
        }

        public void packageInstalledResult(String packageName, int returnCode, Bundle extras) {
            Log.i(TAG, "Installed " + packageName + ":" + returnCode);
            String apkUrl = mApkInfo.getApkUri().getPath();
            if (!apkUrl.isEmpty()) {
                if (extras != null) {
                    int status = extras.getInt(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                    if (status != PackageInstaller.STATUS_SUCCESS) {
                        mDelegate.onInstallFailure(apkUrl, "onReceiveResult:" + status + "," + returnCode + ",android:" + Build.VERSION.SDK_INT);
                    } else {
                        mDelegate.onInstallSuccess(apkUrl);
                    }
                } else {
                    mDelegate.onInstallFailure(apkUrl, "onReceiveResultReturnCode" + returnCode + ",android:" + Build.VERSION.SDK_INT);
                }
            } else {
                mDelegate.onInstallFailure("", "onReceiveResult:APKABSOLUTEPATH_IS_NULL");
            }
        }

        /**
         * On some devices java.lang.NoSuchMethodError may occur, packageInstalledResult call fails, manually retry here
         */
        public void reNotifyResultOnError(String packageName, int returnCode, Bundle extras) {
            packageInstalledResult(packageName, returnCode, extras);
        }

    }

    class InstallManagerInfo {
        public static final int INSTALL_SUCCEEDED = 1;
        public static final int ERROR_NO_ENOUGH_SPACE_AFTER_INSTALL = 11;  //Insufficient space during installation
        public static final int ERROR_INSTALL_COMMIT_FAIL = 17;
        /**
         * Installation return code: this is passed in the {PackageInstaller#EXTRA_LEGACY_STATUS}
         * if the package manager service found that the device didn't have enough storage space to
         * install the app.
         *
         * @hide
         */
        public static final int INSTALL_FAILED_INSUFFICIENT_STORAGE = -4;
        /**
         * Installation return code: this is passed in the {PackageInstaller#EXTRA_LEGACY_STATUS}
         * if a previously installed package of the same name has a different signature than the new
         * package (and the old package's data was not removed).
         *
         * @hide
         */
        public static final int INSTALL_FAILED_UPDATE_INCOMPATIBLE = -7;
        /**
         * Installation return code: this is passed in the {PackageInstaller#EXTRA_LEGACY_STATUS}
         * if the new package couldn't be installed because the verification did not succeed.
         *
         * @hide
         */
        public static final int INSTALL_FAILED_VERIFICATION_FAILURE = -22;
        /**
         * Installation parse return code: this is passed in the
         * {PackageInstaller#EXTRA_LEGACY_STATUS} if the parser encountered some structural
         * problem in the manifest.
         *
         * @hide
         */
        public static final int INSTALL_PARSE_FAILED_MANIFEST_MALFORMED = -108;
    }


    class InstallResultBroadcastReceiver extends BroadcastReceiver {
        private static final String TAG = "SessionInstallReceiver";
        private static final String EXTRA_LEGACY_STATUS = "android.content.pm.extra.LEGACY_STATUS";
        public static final String ACTION_INSTALL_RESULT = "com.xiaomo.androidforclaw.action.INSTALL_RESULT";
        // legacyStatus has no universal error code available; use undefined error code here so it can be converted to store error code ERROR_INSTALL_DEFAULT_FAIL in PackageInstallObserver
        private static final int STATUS_UNKNOWN = -10000;

        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "Broadcast receiver received Intent: " + intent);
            Log.d(TAG, "Intent Action: " + intent.getAction());
            Log.d(TAG, "Intent Data: " + intent.getDataString());
            Log.d(TAG, "Intent Extras: " + intent.getExtras());

            String action = intent.getAction();
            Log.d(TAG, "Checking Action match: " + action + " == " + ACTION_INSTALL_RESULT);

            if (ACTION_INSTALL_RESULT.equals(action)) {
                Log.d(TAG, "Action matched, processing install result");

                String packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME);
                Log.d(TAG, "Package name from EXTRA_PACKAGE_NAME: " + packageName);

                if (TextUtils.isEmpty(packageName)) {
                    packageName = intent.getStringExtra("packageName"); // Non-standard parameter format when intercepted by CustomOS, handle for compatibility
                    Log.d(TAG, "Package name from packageName: " + packageName);
                }

                int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                String message = intent.getStringExtra(EXTRA_STATUS_MESSAGE);

                Log.d(TAG, "Install status: " + status);
                Log.d(TAG, "Status message: " + message);
                Log.d(TAG, "Package name: " + packageName);

                if (status != PackageInstaller.STATUS_SUCCESS) {
                    Log.e(TAG, String.format(Locale.getDefault(), "install %s failed with [status=%d,message=%s]", packageName, status, message));
                } else {
                    Log.d(TAG, "Installation successful: " + packageName);
                }

                handleSessionInstallByNormal(context, intent, packageName, status);
            } else {
                Log.w(TAG, "Action mismatch, ignoring broadcast: " + action);
            }
        }

        private void handleSessionInstallByNormal(Context context, final Intent intent, final String packageName, final int status) {
            Log.d(TAG, "Processing install result, package: " + packageName + ", status: " + status);

            int legacyStatus;
            if (intent.hasExtra(EXTRA_LEGACY_STATUS)) {
                legacyStatus = intent.getIntExtra(EXTRA_LEGACY_STATUS, STATUS_UNKNOWN);
                Log.d(TAG, "Legacy status from EXTRA_LEGACY_STATUS: " + legacyStatus);
            } else {
                Log.d(TAG, "No EXTRA_LEGACY_STATUS, converting legacy status from status");
                switch (status) {
                    case PackageInstaller.STATUS_SUCCESS:
                        legacyStatus = InstallManagerInfo.INSTALL_SUCCEEDED;
                        Log.d(TAG, "STATUS_SUCCESS -> INSTALL_SUCCEEDED");
                        break;
                    case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                    case PackageInstaller.STATUS_FAILURE_CONFLICT:
                        legacyStatus = InstallManagerInfo.INSTALL_FAILED_UPDATE_INCOMPATIBLE;
                        Log.d(TAG, "STATUS_FAILURE_INCOMPATIBLE/CONFLICT -> INSTALL_FAILED_UPDATE_INCOMPATIBLE");
                        break;
                    case PackageInstaller.STATUS_FAILURE_BLOCKED:
                        legacyStatus = InstallManagerInfo.INSTALL_FAILED_VERIFICATION_FAILURE;
                        Log.d(TAG, "STATUS_FAILURE_BLOCKED -> INSTALL_FAILED_VERIFICATION_FAILURE");
                        break;
                    case PackageInstaller.STATUS_FAILURE_INVALID:
                        legacyStatus = InstallManagerInfo.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                        Log.d(TAG, "STATUS_FAILURE_INVALID -> INSTALL_PARSE_FAILED_MANIFEST_MALFORMED");
                        break;
                    case PackageInstaller.STATUS_FAILURE_STORAGE:
                        legacyStatus = InstallManagerInfo.INSTALL_FAILED_INSUFFICIENT_STORAGE;
                        Log.d(TAG, "STATUS_FAILURE_STORAGE -> INSTALL_FAILED_INSUFFICIENT_STORAGE");
                        break;
                    case PackageInstaller.STATUS_FAILURE:
                    case PackageInstaller.STATUS_FAILURE_ABORTED:
                    case PackageInstaller.STATUS_PENDING_USER_ACTION:
                    default:
                        legacyStatus = STATUS_UNKNOWN;
                        Log.d(TAG, "Other status -> STATUS_UNKNOWN");
                        break;
                }
            }

            Log.d(TAG, "Final legacy status: " + legacyStatus);

            if (TextUtils.isEmpty(packageName)) {
                Log.e(TAG, "Package name is empty, cannot process install result");
                return;
            }

            final int resultCode = legacyStatus;
            Log.d(TAG, "Notifying install result, package: " + packageName + ", result code: " + resultCode);
            Log.d(TAG, "Current observer count: " + sObservers.size());
            Log.d(TAG, "Observer list: " + sObservers.keySet());

            InstallManager.notifyResult(packageName, resultCode, intent.getExtras());
            Log.d(TAG, "Install result notification complete");
        }

    }

    public interface PackageInstallObserver {

        void onInstallStart(String fileUrl);

        void onInstallSuccess(String fileUrl);

        void onInstallFailure(String fileUrl, String errorMsg);

    }

    // Install result listener
    private static class InstallResultListener implements IPackageInstallObserver {
        private final String packageName;
        private final PackageInstallObserver observer;
        private Handler handler;
        private Runnable timeoutRunnable;

        public InstallResultListener(String packageName, PackageInstallObserver observer) {
            this.packageName = packageName;
            this.observer = observer;
        }

        public void setHandler(Handler handler, Runnable timeoutRunnable) {
            this.handler = handler;
            this.timeoutRunnable = timeoutRunnable;
        }

        @Override
        public void packageInstalledResult(String packageName, int returnCode, Bundle extras) {
            Log.d(TAG, "Install result received: " + packageName + ", return code: " + returnCode);

            // Cancel timeout handling
            if (handler != null && timeoutRunnable != null) {
                handler.removeCallbacks(timeoutRunnable);
            }

            if (returnCode == InstallManagerInfo.INSTALL_SUCCEEDED) {
                Log.d(TAG, "APK installed successfully: " + packageName);
                observer.onInstallSuccess("Installation successful");
            } else {
                Log.e(TAG, "APK installation failed: " + packageName + ", return code: " + returnCode);
                String errorMsg = "Installation failed, return code: " + returnCode;
                observer.onInstallFailure("", errorMsg);
            }

            // Clean up observer
            sObservers.remove(packageName);
        }

        @Override
        public void reNotifyResultOnError(String packageName, int returnCode, Bundle extras) {
            packageInstalledResult(packageName, returnCode, extras);
        }
    }
}