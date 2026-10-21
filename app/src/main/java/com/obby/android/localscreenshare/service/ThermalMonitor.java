package com.obby.android.localscreenshare.service;

import android.content.Context;
import android.os.Build;
import android.os.PowerManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.obby.android.localscreenshare.core.stream.ThermalThrottlePolicy;

/**
 * Keeps the service informed about the platform thermal status.
 *
 * <p>The thermal status exists from API 29, and the whole class is annotated
 * {@link RequiresApi} for that reason: the owner has to check the SDK level with an explicit
 * {@code Build.VERSION.SDK_INT} comparison in the method that constructs it, which is also what
 * lint needs to see. Keeping the platform calls in this class keeps them out of the service and
 * out of code paths that also run on API 25-28.</p>
 *
 * <p><b>Initial status.</b> The platform listener only promises change notifications, so the
 * status that is already current is delivered explicitly after registration. Without that, a device
 * that is hot before sharing starts would only be throttled after its next status transition.</p>
 *
 * <p><b>Threading.</b> The listener is invoked on whatever thread the platform uses; the owner posts
 * to its own handler, so this class makes no threading promise of its own. {@link #start()} and
 * {@link #stop()} are idempotent and never throw: a device whose thermal service is missing keeps
 * the user's settings instead of failing the share.</p>
 */
@RequiresApi(Build.VERSION_CODES.Q)
final class ThermalMonitor {

    /**
     * Receives every thermal status, including the current one at {@link #start()}.
     */
    interface Listener {

        /**
         * Called with one platform thermal status.
         *
         * @param status one of the {@code PowerManager.THERMAL_STATUS_*} values
         */
        void onThermalStatusChanged(int status);
    }

    @Nullable
    private final PowerManager mPowerManager;

    @NonNull
    private final Listener mListener;

    @NonNull
    private final PowerManager.OnThermalStatusChangedListener mStatusListener;

    // True between a successful start() and stop().
    private boolean mStarted;

    /**
     * Creates a monitor for one owner.
     *
     * @param context  any context of the process; required
     * @param listener receiver of the status values; required
     */
    ThermalMonitor(@NonNull final Context context, @NonNull final Listener listener) {
        mPowerManager = context.getSystemService(PowerManager.class);
        mListener = listener;
        mStatusListener = mListener::onThermalStatusChanged;
    }

    /**
     * Registers the platform listener and delivers the status that is already current.
     *
     * @return {@code true} when the listener is registered
     */
    boolean start() {
        if (mStarted) {
            return true;
        }
        if (mPowerManager == null) {
            return false;
        }
        try {
            mPowerManager.addThermalStatusListener(mStatusListener);
        } catch (RuntimeException e) {
            return false;
        }
        mStarted = true;
        mListener.onThermalStatusChanged(getCurrentStatus());
        return true;
    }

    /**
     * Removes the platform listener; idempotent.
     */
    void stop() {
        if (!mStarted) {
            return;
        }
        mStarted = false;
        try {
            mPowerManager.removeThermalStatusListener(mStatusListener);
        } catch (RuntimeException ignored) {
            // The monitor is going away; a failing removal must not affect the teardown.
        }
    }

    /**
     * Reads the status the platform reports right now.
     *
     * @return the current status, or {@code ThermalThrottlePolicy.STATUS_NONE} when unavailable
     */
    int getCurrentStatus() {
        if (mPowerManager == null) {
            return ThermalThrottlePolicy.STATUS_NONE;
        }
        try {
            return mPowerManager.getCurrentThermalStatus();
        } catch (RuntimeException e) {
            return ThermalThrottlePolicy.STATUS_NONE;
        }
    }
}
