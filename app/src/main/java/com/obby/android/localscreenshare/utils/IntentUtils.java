package com.obby.android.localscreenshare.utils;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.annotation.NonNull;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class IntentUtils {
    private static final String SCHEME_PACKAGE = "package";

    @NonNull
    public static Intent createAppNotificationSettingsIntent(@NonNull final Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
        } else {
            return createApplicationDetailsSettings(context);
        }
    }

    @NonNull
    public static Intent createManageOverlayPermissionIntent(@NonNull final Context context) {
        return new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            .setData(Uri.fromParts(SCHEME_PACKAGE, context.getPackageName(), null));
    }

    @SuppressLint("BatteryLife")
    @NonNull
    public static Intent createRequestIgnoreBatteryOptimizationsIntent(@NonNull final Context context) {
        return new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.fromParts(SCHEME_PACKAGE, context.getPackageName(), null));
    }

    @NonNull
    public static Intent createApplicationDetailsSettings(@NonNull final Context context) {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts(SCHEME_PACKAGE, context.getPackageName(), null));
    }
}
