/**
 * OpenClaw Source Reference:
 * - No OpenClaw equivalent (Android platform specific)
 */
package com.xiaomo.androidforclaw.data.model;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;

import com.xiaomo.androidforclaw.core.MyApplication;

import java.io.File;

/**
 * Created by zhangjiahao on 17-11-22.
 */

public class ApkInfo {

    private Uri mApkUri;
    private String mApkPackageName;
    private File mApk;
    private static final String TAG = "Installsupport";

    public ApkInfo(Uri apkUri) {
        mApkUri = apkUri;
    }

    public boolean prepare() {
        Log.d(TAG, "Preparing APK info, URI: " + mApkUri);
        
        mApk = getApkFile(mApkUri);
        Log.d(TAG, "APK file path: " + mApk.getAbsolutePath());
        Log.d(TAG, "APK file exists: " + mApk.exists());
        Log.d(TAG, "APK file readable: " + mApk.canRead());
        Log.d(TAG, "APK file size: " + mApk.length());
        
        // Even if the file is not readable, still try to parse APK info
        if (mApk.exists() && mApk.length() > 0) {
            Log.d(TAG, "Starting APK package info parsing");
            try {
                PackageInfo packageInfo = MyApplication.application
                        .getApplicationContext()
                        .getPackageManager()
                        .getPackageArchiveInfo(
                                mApk.getAbsolutePath(),
                                PackageManager.GET_SIGNATURES);
 
                Log.d(TAG, "PackageInfo parse result: " + (packageInfo != null));
 
                if (packageInfo != null) {
                    mApkPackageName = packageInfo.packageName;
                    Log.d(TAG, "APK package name: " + mApkPackageName);
                    Log.d(TAG, "APK version: " + packageInfo.versionName);
                    Log.d(TAG, "APK version code: " + packageInfo.versionCode);
                    return true;
                } else {
                    Log.e(TAG, "PackageInfo is null, APK file may be corrupted or invalid");
                }
            } catch (Exception e) {
                Log.e(TAG, "Exception while parsing APK: " + e.getMessage(), e);
            }
        } else {
            Log.e(TAG, "APK file does not exist or size is 0");
        }
        return false;
    }

    private File getApkFile(Uri uri) {
        return new File(uri.getPath());
    }

    public Uri getApkUri() {
        return Uri.fromFile(mApk);
    }

    public String getApkPackageName() {
        return mApkPackageName;
    }

    @Override
    public String toString() {
        return "ApkInfo{" +
                "mApkUri=" + getApkUri() +
                ", mApkPackageName='" + getApkPackageName() + '\'' +
                '}';
    }

}
