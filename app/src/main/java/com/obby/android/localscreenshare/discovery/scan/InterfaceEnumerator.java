package com.obby.android.localscreenshare.discovery.scan;

import androidx.annotation.NonNull;

import java.util.List;

/**
 * Supplies the interfaces a LAN scan may use.
 *
 * <p>The interface exists so {@link ScanCoordinator} can be tested without Android: production
 * reads {@code ConnectivityManager} and {@code LinkProperties}, a test returns literals. The
 * returned list is expected to be already filtered by {@link InterfaceFilter}; an enumerator that
 * does not filter is a bug, because the coordinator does not filter again.</p>
 */
public interface InterfaceEnumerator {

    /** Returns the eligible IPv4 interfaces of this device, possibly empty. */
    @NonNull
    List<LocalInterface> enumerate();
}
