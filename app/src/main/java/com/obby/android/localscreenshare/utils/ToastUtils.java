package com.obby.android.localscreenshare.utils;

import android.content.Context;
import android.widget.Toast;

import androidx.annotation.AnyThread;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.Optional;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ToastUtils {
    private static final Object TOAST_LOCK = new Object();

    private static volatile WeakReference<Toast> sToast;

    @AnyThread
    public static void showToast(@NonNull final Context context, @NonNull final CharSequence text,
        @Duration final int duration) {
        ThreadUtils.runOnMainThread(() -> showToast(Toast.makeText(context, text, duration)));
    }

    @AnyThread
    public static void showToast(@NonNull final Context context, @StringRes final int resId,
        @Duration final int duration) {
        ThreadUtils.runOnMainThread(() -> showToast(Toast.makeText(context, resId, duration)));
    }

    public static void showToast(@NonNull final Toast toast) {
        synchronized (TOAST_LOCK) {
            Optional.ofNullable(sToast).map(WeakReference::get).ifPresent(Toast::cancel);
            sToast = new WeakReference<>(toast);
            toast.show();
        }
    }

    @IntDef({Toast.LENGTH_SHORT, Toast.LENGTH_LONG})
    @Retention(RetentionPolicy.SOURCE)
    private @interface Duration {
    }
}
