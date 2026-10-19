package com.obby.android.localscreenshare.utils;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.annotation.NonNull;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Builds the system settings intents the app uses to send the user to a permission or app screen.
 *
 * <p>Every factory returns an intent meant for {@code startActivity}; no resolution is performed
 * here, so only the device decides whether the target settings screen exists.</p>
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class IntentUtils {
    private static final String SCHEME_PACKAGE = "package";

    /**
     * Notification settings for this app: the per-app notification page on API 26+, the
     * application details page below that because the per-app page does not exist there.
     */
    @NonNull
    public static Intent createAppNotificationSettingsIntent(@NonNull final Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
        } else {
            return createApplicationDetailsSettings(context);
        }
    }

    /** Overlay permission page for this app, addressed through a {@code package:} URI. */
    @NonNull
    public static Intent createManageOverlayPermissionIntent(@NonNull final Context context) {
        return new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            .setData(Uri.fromParts(SCHEME_PACKAGE, context.getPackageName(), null));
    }

    /** Application details page for this app, addressed through a {@code package:} URI. */
    @NonNull
    public static Intent createApplicationDetailsSettings(@NonNull final Context context) {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts(SCHEME_PACKAGE, context.getPackageName(), null));
    }
}
