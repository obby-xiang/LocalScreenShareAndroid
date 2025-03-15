package com.obby.android.localscreenshare.utils;

import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.view.Display;
import android.view.WindowManager;

import androidx.annotation.NonNull;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class WindowUtils {
    @NonNull
    public static Rect getMaximumWindowBounds(@NonNull final Context context) {
        final WindowManager windowManager = context.getSystemService(WindowManager.class);
        final Rect bounds;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            bounds = windowManager.getMaximumWindowMetrics().getBounds();
        } else {
            final Display display = windowManager.getDefaultDisplay();
            final Point displaySize = new Point();
            display.getRealSize(displaySize);
            bounds = new Rect(0, 0, displaySize.x, displaySize.y);
        }

        return bounds;
    }
}
