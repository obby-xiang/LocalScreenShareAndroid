package com.obby.android.localscreenshare.discovery.udp;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Multicast channel defaults, socket limits and announcement cadence of the UDP discovery channel.
 *
 * <p>The group and port are user-configurable and act as the fallback when no setting is stored;
 * the remaining values bound the sockets and schedule the consecutive startup, heartbeat and
 * goodbye announcements.</p>
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class MulticastConstants {
    /**
     * Default multicast group used by the discovery channel. It is administratively scoped
     * (224.0.0.0/8) and never routed, so announcements stay on the local link; the group and the
     * port are user-configurable through
     * {@link com.obby.android.localscreenshare.settings.Preferences}.
     */
    public static final String MULTICAST_GROUP = "224.0.0.167";

    /**
     * Default multicast port; distinct from other local-discovery products on the same group.
     */
    public static final int MULTICAST_PORT = 53535;

    /**
     * Multicast hop limit: 1 keeps announcements on the local link.
     */
    public static final int MULTICAST_TTL = 1;

    /**
     * Maximum number of interfaces that get their own announcement socket.
     */
    public static final int MULTICAST_MAX_INTERFACES = 4;

    /**
     * Receive buffer of one multicast socket; announcements are at most 512 bytes.
     */
    public static final int MULTICAST_RECEIVE_BUFFER = 65_536;

    /**
     * Delays of the three startup announcements, in milliseconds.
     */
    public static final long[] MULTICAST_BURST_DELAYS_MS = {100L, 500L, 2_000L};

    /**
     * Delays of the three goodbye announcements sent when the server stops.
     */
    public static final long[] MULTICAST_GOODBYE_DELAYS_MS = {0L, 100L, 300L};

    /**
     * Heartbeat period while a capture session is live.
     */
    public static final long MULTICAST_HEARTBEAT_ACTIVE_MS = 5_000L;

    /**
     * Heartbeat period while the server is idle.
     */
    public static final long MULTICAST_HEARTBEAT_IDLE_MS = 15_000L;

    /**
     * Consecutive receive errors after which one socket is rebuilt.
     */
    public static final int MULTICAST_MAX_RECEIVE_ERRORS = 10;
}
