package com.obby.android.localscreenshare.discovery.model;

/**
 * Discovery source that produced an observation.
 *
 * <p>One device can be observed through several channels at once; the store merges them into a
 * single entry and keeps the per-channel addresses, which is why a channel is part of an address
 * key rather than of the device identity.</p>
 */
public enum DiscoveryChannel {
    /** Android NSD (mDNS/DNS-SD), either the current or the legacy service type. */
    NSD,

    /** UDP multicast announcement. */
    MULTICAST,

    /** Direct address scan. */
    SCAN,

    /** Address entered by the user. */
    MANUAL,

    /** Persisted favorite probe. */
    FAVORITE
}
