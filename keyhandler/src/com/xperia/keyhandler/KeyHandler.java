/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xperia.keyhandler;

import android.app.ActivityTaskManager;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;

import com.android.internal.os.DeviceKeyHandler;

import java.util.Set;

/**
 * Xperia camera key. What it launches is chosen in Settings (XperiaCamera):
 * 0 = off (the key is left to the default handling), 1 = camera, 2 = Photo Pro.
 *
 * While a camera app is in the foreground the key is passed to the app untouched so that it
 * can be used as the shutter button.
 */
public class KeyHandler implements DeviceKeyHandler {
    private static final String TAG = "XperiaKeyHandler";

    // Settings.System, written by XperiaCamera
    private static final String SETTING_CAMERA_KEY = "xperia_camera_key_action";
    private static final int ACTION_OFF = 0;
    private static final int ACTION_CAMERA = 1;
    private static final int ACTION_PHOTO_PRO = 2;

    private static final String PKG_CAMERA = "com.sonyericsson.android.camera";
    private static final String PKG_PHOTO_PRO = "com.sonymobile.photopro";
    private static final Set<String> CAMERA_PACKAGES = Set.of(
            PKG_CAMERA,
            PKG_PHOTO_PRO,
            "jp.co.sony.mc.videopro",
            "com.sonymobile.cinemapro",
            "com.sonymobile.addoncamera.portraitselfie",
            "com.sonyericsson.android.addoncamera.artfilter",
            "org.lineageos.aperture");

    private final Context mContext;
    private final PowerManager mPowerManager;
    private final KeyguardManager mKeyguardManager;

    public KeyHandler(Context context) {
        mContext = context;
        mPowerManager = context.getSystemService(PowerManager.class);
        mKeyguardManager = context.getSystemService(KeyguardManager.class);
    }

    @Override
    public KeyEvent handleKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_CAMERA) {
            return event;
        }
        final int action = getAction();
        if (action == ACTION_OFF) {
            return event;
        }
        // A camera app is in front: it uses the key as the shutter.
        if (mPowerManager.isInteractive() && isCameraAppForeground()) {
            return event;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            launch(action);
        }
        // Consume both down and up.
        return null;
    }

    private int getAction() {
        try {
            return Settings.System.getIntForUser(mContext.getContentResolver(),
                    SETTING_CAMERA_KEY, ACTION_CAMERA, UserHandle.USER_CURRENT);
        } catch (Exception e) {
            return ACTION_CAMERA;
        }
    }

    private void launch(int action) {
        final boolean locked = mKeyguardManager != null && mKeyguardManager.isKeyguardLocked();
        final PackageManager pm = mContext.getPackageManager();
        Intent intent = null;

        if (!locked) {
            final String pkg = action == ACTION_PHOTO_PRO ? PKG_PHOTO_PRO : PKG_CAMERA;
            intent = pm.getLaunchIntentForPackage(pkg);
        }
        if (intent == null) {
            intent = new Intent(locked
                    ? MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE
                    : MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        if (!mPowerManager.isInteractive()) {
            mPowerManager.wakeUp(SystemClock.uptimeMillis(),
                    PowerManager.WAKE_REASON_CAMERA_LAUNCH, "xperia:camerakey");
        }
        try {
            mContext.startActivityAsUser(intent, UserHandle.CURRENT_OR_SELF);
        } catch (Exception e) {
            Log.w(TAG, "Could not launch camera", e);
        }
    }

    private boolean isCameraAppForeground() {
        try {
            final ActivityTaskManager.RootTaskInfo info =
                    ActivityTaskManager.getService().getFocusedRootTaskInfo();
            if (info == null || info.topActivity == null) {
                return false;
            }
            final String pkg = info.topActivity.getPackageName();
            if (CAMERA_PACKAGES.contains(pkg)) {
                return true;
            }
            // Whatever app handles the still image camera intent (e.g. a replaced default).
            final ResolveInfo ri = mContext.getPackageManager().resolveActivity(
                    new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
                    PackageManager.MATCH_DEFAULT_ONLY);
            return ri != null && ri.activityInfo != null && pkg.equals(ri.activityInfo.packageName);
        } catch (Exception e) {
            return false;
        }
    }
}
