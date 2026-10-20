package com.obby.android.localscreenshare.discovery.store;

import androidx.annotation.NonNull;

import com.obby.android.localscreenshare.discovery.model.DeviceInfo;

import java.util.List;

/**
 * Receives the device snapshot whenever it changes.
 *
 * <p>Called on the main thread with an unmodifiable list that is replaced, never mutated, on the
 * next change; a listener may call back into the store and may remove itself during the callback.
 * The callback is only invoked when the new snapshot differs from the previous one.</p>
 */
@FunctionalInterface
public interface DeviceStoreListener {

    /**
     * Called with the new snapshot; {@code devices} is never null and never mutated.
     */
    void onDevicesChanged(@NonNull List<DeviceInfo> devices);
}
