package com.obby.android.localscreenshare.discovery.model;

/**
 * Reachability of a device as derived by the store.
 *
 * <p>Discovery sources only ever produce {@link #HEARD}: an advertisement or a resolved NSD
 * service proves that something announced itself, not that it accepts a connection. Only an
 * application-level confirmation promotes a device to {@link #CONFIRMED}. {@link #STALE} means the
 * last observation is older than the heard window but the entry is still retained, and
 * {@link #UNREACHABLE} is the terminal state of a favorite that has expired.</p>
 */
public enum DevicePresence {
    /** Observed by at least one discovery source within the heard window, never confirmed. */
    HEARD,

    /** Application-level confirmation received within the confirmed window. */
    CONFIRMED,

    /** Last observation is older than the heard window; entry retained until the remove window. */
    STALE,

    /** Favorite whose last observation is older than the remove window; kept for retry. */
    UNREACHABLE
}
