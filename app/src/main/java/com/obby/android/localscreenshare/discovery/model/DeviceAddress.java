package com.obby.android.localscreenshare.discovery.model;

import androidx.annotation.NonNull;

import java.util.Objects;

/**
 * One address a device was observed at, tagged with the channel that observed it.
 *
 * <p>Instances are immutable. The store keeps one address per {@link #getKey() key}, which is the
 * channel plus host plus port, so a device seen over NSD and over multicast has two addresses and
 * one device entry. {@link #compareTo(DeviceAddress)} orders usable addresses first: confirmed
 * before unconfirmed, IPv4 before IPv6, and the most recently confirmed before older ones.</p>
 */
public final class DeviceAddress implements Comparable<DeviceAddress> {

    /** Address family of IPv4 literals. */
    public static final int FAMILY_IPV4 = 4;

    /** Address family of IPv6 literals, stored with surrounding brackets. */
    public static final int FAMILY_IPV6 = 6;

    @NonNull
    private final String mHost;

    private final int mPort;

    private final int mFamily;

    @NonNull
    private final DiscoveryChannel mChannel;

    private final boolean mConfirmed;

    private final long mLastConfirmedMs;

    /**
     * Creates an unconfirmed address and derives the family from the host literal.
     *
     * @param host IPv4 literal or bracketed IPv6 literal
     * @param port port to connect to
     * @param channel channel that observed the address
     */
    public DeviceAddress(@NonNull final String host, final int port, @NonNull final DiscoveryChannel channel) {
        this(host, port, channel, false, 0L);
    }

    /**
     * Creates an address.
     *
     * @param host IPv4 literal or bracketed IPv6 literal
     * @param port port to connect to
     * @param channel channel that observed the address
     * @param confirmed whether an application-level confirmation accepted this address
     * @param lastConfirmedMs time of the last confirmation, or {@code 0} when never confirmed
     * @throws IllegalArgumentException if {@code host} is blank or {@code port} is out of range
     */
    public DeviceAddress(@NonNull final String host, final int port, @NonNull final DiscoveryChannel channel,
        final boolean confirmed, final long lastConfirmedMs) {
        if (host.trim().isEmpty()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < 0 || port > 0xFFFF) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        mHost = host;
        mPort = port;
        mFamily = familyOf(host);
        mChannel = Objects.requireNonNull(channel, "channel");
        mConfirmed = confirmed;
        mLastConfirmedMs = lastConfirmedMs;
    }

    /** Derives the address family from a formatted literal. */
    public static int familyOf(@NonNull final String host) {
        return host.startsWith("[") || host.indexOf(':') >= 0 ? FAMILY_IPV6 : FAMILY_IPV4;
    }

    @NonNull
    public String getHost() {
        return mHost;
    }

    public int getPort() {
        return mPort;
    }

    public int getFamily() {
        return mFamily;
    }

    @NonNull
    public DiscoveryChannel getChannel() {
        return mChannel;
    }

    public boolean isConfirmed() {
        return mConfirmed;
    }

    public long getLastConfirmedMs() {
        return mLastConfirmedMs;
    }

    /** Returns the merge key: channel, host and port separated by {@code |}. */
    @NonNull
    public String getKey() {
        return mChannel.name() + "|" + mHost + "|" + mPort;
    }

    /** Returns an address for the same host, port and channel with the given confirmation state. */
    @NonNull
    public DeviceAddress withConfirmation(final boolean confirmed, final long lastConfirmedMs) {
        return new DeviceAddress(mHost, mPort, mChannel, confirmed, lastConfirmedMs);
    }

    /**
     * Orders confirmed addresses first, then IPv4 before IPv6, then most recently confirmed first,
     * then by host and port so the order is total.
     */
    @Override
    public int compareTo(@NonNull final DeviceAddress other) {
        if (mConfirmed != other.mConfirmed) {
            return mConfirmed ? -1 : 1;
        }
        if (mFamily != other.mFamily) {
            return mFamily < other.mFamily ? -1 : 1;
        }
        final int byConfirmedTime = Long.compare(other.mLastConfirmedMs, mLastConfirmedMs);
        if (byConfirmedTime != 0) {
            return byConfirmedTime;
        }
        final int byHost = mHost.compareTo(other.mHost);
        if (byHost != 0) {
            return byHost;
        }
        return Integer.compare(mPort, other.mPort);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DeviceAddress)) {
            return false;
        }
        final DeviceAddress that = (DeviceAddress) o;
        return mPort == that.mPort && mFamily == that.mFamily && mConfirmed == that.mConfirmed
            && mLastConfirmedMs == that.mLastConfirmedMs && mHost.equals(that.mHost) && mChannel == that.mChannel;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mHost, mPort, mFamily, mChannel, mConfirmed, mLastConfirmedMs);
    }

    @Override
    public String toString() {
        return "DeviceAddress{"
            + "host='" + mHost + '\''
            + ", port=" + mPort
            + ", family=" + mFamily
            + ", channel=" + mChannel
            + ", confirmed=" + mConfirmed
            + ", lastConfirmedMs=" + mLastConfirmedMs
            + '}';
    }
}
