package com.obby.android.localscreenshare.discovery.udp;

import androidx.annotation.NonNull;


import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;

/**
 * Immutable multicast channel configuration.
 *
 * <p>The group must be a multicast IPv4 literal; the port must be a valid UDP port. Both values
 * are user-configurable through {@code Preferences}, which is why an invalid configuration is
 * rejected with an exception at construction instead of silently falling back in the middle of a
 * socket setup.</p>
 */
public final class MulticastConfig {

    @NonNull
    private final String mGroup;

    private final int mPort;

    private final int mTtl;

    private final int mMaxInterfaces;

    /**
     * Creates a configuration with the default TTL and interface bound.
     *
     * @throws IllegalArgumentException if the group is not multicast IPv4 or the port is invalid
     */
    public MulticastConfig(@NonNull final String group, final int port) {
        this(group, port, MulticastConstants.MULTICAST_TTL, MulticastConstants.MULTICAST_MAX_INTERFACES);
    }

    /**
     * Creates a configuration.
     *
     * @param group multicast group address
     * @param port UDP port
     * @param ttl multicast hop limit
     * @param maxInterfaces maximum number of sockets opened
     * @throws IllegalArgumentException if any value is out of range
     */
    public MulticastConfig(@NonNull final String group, final int port, final int ttl,
        final int maxInterfaces) {
        if (group == null || group.trim().isEmpty()) {
            throw new IllegalArgumentException("group must not be blank");
        }
        if (port < 1 || port > 0xFFFF) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        if (ttl < 0 || ttl > 255) {
            throw new IllegalArgumentException("ttl out of range: " + ttl);
        }
        if (maxInterfaces < 1) {
            throw new IllegalArgumentException("maxInterfaces must be positive: " + maxInterfaces);
        }
        try {
            final InetAddress address = InetAddress.getByName(group.trim());
            if (!address.isMulticastAddress()) {
                throw new IllegalArgumentException("group is not multicast: " + group);
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("group is not a literal address: " + group, e);
        }
        mGroup = group.trim();
        mPort = port;
        mTtl = ttl;
        mMaxInterfaces = maxInterfaces;
    }

    /** Returns the configured group literal. */
    @NonNull
    public String getGroup() {
        return mGroup;
    }

    /** Returns the multicast port. */
    public int getPort() {
        return mPort;
    }

    /** Returns the multicast hop limit. */
    public int getTtl() {
        return mTtl;
    }

    /** Returns the maximum number of interface sockets. */
    public int getMaxInterfaces() {
        return mMaxInterfaces;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MulticastConfig)) {
            return false;
        }
        final MulticastConfig that = (MulticastConfig) o;
        return mPort == that.mPort && mTtl == that.mTtl && mMaxInterfaces == that.mMaxInterfaces
            && mGroup.equals(that.mGroup);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mGroup, mPort, mTtl, mMaxInterfaces);
    }

    @Override
    @NonNull
    public String toString() {
        return "MulticastConfig{group=" + mGroup + ", port=" + mPort + ", ttl=" + mTtl
            + ", maxInterfaces=" + mMaxInterfaces + "}";
    }
}
