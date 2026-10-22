package com.obby.android.localscreenshare.support;

import android.app.Dialog;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.NonNull;

/**
 * Attaches, moves and removes overlay windows and dialogs without letting a revoked permission
 * crash the process.
 *
 * <p>Both services draw their floating UI with the overlay window type. When the user revokes
 * "display over other apps" while a share is running, the next window or dialog call throws
 * {@link WindowManager.BadTokenException} or {@link SecurityException} on the main looper, where
 * nothing catches it and the whole process dies together with the session, the notification and the
 * ongoing media. These helpers swallow those failures, log one warning and report the outcome
 * through their return value, so the session can keep running with the notification and the
 * activity as its remaining entry points.</p>
 *
 * <p>Every call is idempotent: attaching a view that already has a parent and detaching a view that
 * was never attached are no-ops. {@link #update} also skips detached views, because a revoked
 * permission removes the window out from under an animation that is still running.</p>
 */
public final class OverlayWindow {

    private static final String TAG = "OverlayWindow";

    private OverlayWindow() {
    }

    /**
     * Adds an overlay view unless it is already attached.
     *
     * @param windowManager window manager of the service context; required
     * @param view          view to add; required
     * @param params        layout params of the overlay window; required
     * @return {@code true} when the view is attached afterwards, {@code false} when the platform
     *     refused the call (typically a revoked overlay permission)
     */
    public static boolean attach(@NonNull final WindowManager windowManager,
                                 @NonNull final View view,
                                 @NonNull final WindowManager.LayoutParams params) {
        if (view.getParent() != null) {
            return true;
        }
        try {
            windowManager.addView(view, params);
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "attach: could not add " + view.getClass().getSimpleName()
                + ", overlay permission may have been revoked", e);
            return false;
        }
    }

    /**
     * Removes an overlay view if it is attached.
     *
     * @param windowManager window manager of the service context; required
     * @param view          view to remove; required
     * @return {@code true} when the view is detached afterwards, {@code false} when the platform
     *     refused the call
     */
    public static boolean detach(@NonNull final WindowManager windowManager,
                                 @NonNull final View view) {
        if (view.getParent() == null) {
            return true;
        }
        try {
            windowManager.removeViewImmediate(view);
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "detach: could not remove " + view.getClass().getSimpleName()
                + ", overlay permission may have been revoked", e);
            return false;
        }
    }

    /**
     * Re-applies layout params to an attached overlay view.
     *
     * <p>Silent on purpose: the call runs from animations and per-frame layout updates, and a
     * window the system removed after a permission change must not produce a log line per tick.
     * Callers that need to know treat {@code false} as "the window is gone".</p>
     *
     * @param windowManager window manager of the service context; required
     * @param view          attached overlay view; required
     * @param params        new layout params; required
     * @return {@code true} when the params were applied, {@code false} when the view is not
     *     attached or the platform refused the call
     */
    public static boolean update(@NonNull final WindowManager windowManager,
                                 @NonNull final View view,
                                 @NonNull final WindowManager.LayoutParams params) {
        if (view.getParent() == null) {
            return false;
        }
        try {
            windowManager.updateViewLayout(view, params);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Shows an overlay dialog.
     *
     * @param dialog dialog to show; required
     * @return {@code true} when the dialog was shown, {@code false} when the platform refused it
     */
    public static boolean show(@NonNull final Dialog dialog) {
        try {
            dialog.show();
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "show: could not show " + dialog.getClass().getSimpleName()
                + ", overlay permission may have been revoked", e);
            return false;
        }
    }

    /**
     * Dismisses an overlay dialog; safe when the dialog is not showing.
     *
     * @param dialog dialog to dismiss; required
     * @return {@code true} when the dialog is dismissed, {@code false} when the platform refused
     *     the call
     */
    public static boolean dismiss(@NonNull final Dialog dialog) {
        try {
            dialog.dismiss();
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "dismiss: could not dismiss " + dialog.getClass().getSimpleName(),
                e);
            return false;
        }
    }
}
