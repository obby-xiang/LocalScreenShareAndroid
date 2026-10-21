package com.obby.android.localscreenshare.trust;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Read-only view of the pins, used by the trust manager.
 *
 * <p>The trust manager runs on a transport thread and must not depend on the mutable store's
 * lifecycle; this interface keeps the lookup synchronous and side-effect free. Writes and
 * enumeration stay on {@link TlsPinStore}.</p>
 */
public interface TlsPinLookup {

    /**
     * Returns the pin of {@code id}, or null when the device was never confirmed.
     */
    @Nullable
    TlsPin find(@NonNull String id);
}
