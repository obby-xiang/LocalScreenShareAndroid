package com.obby.android.localscreenshare.discovery.provider;

import androidx.annotation.NonNull;

/**
 * Receives observations from one discovery source.
 *
 * <p>Implementations run on the main thread: providers marshal their network and framework
 * callbacks before calling these methods, so the store does not need a lock. Implementations must
 * not block and must not throw; a provider treats a throwing callback as a bug in the consumer.</p>
 */
public interface DiscoveryProviderListener {

    /** Called for every sighting, loss and goodbye observed by the provider. */
    void onObserved(@NonNull DeviceObservation observation);

    /** Reports a provider-level failure that did not produce an observation. */
    void onProviderError(@NonNull String reason);
}
