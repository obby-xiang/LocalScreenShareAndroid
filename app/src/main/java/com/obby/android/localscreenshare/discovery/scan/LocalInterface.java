package com.obby.android.localscreenshare.discovery.scan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;

/**
 * One IPv4 address of one local network interface, as far as the scanner is concerned.
 *
 * <p>The enumerator hands these values to {@link InterfaceFilter}, which drops the interfaces a
 * LAN scan must not touch. Keeping the transport, the point-to-point flag and the loopback flag on
 * the value instead of re-reading {@code NetworkCapabilities} later makes the filter a pure
 * function that JVM tests can drive with literals.</p>
 */
public final class LocalInterface {

    /** Transport reported by the platform for the network that owns the interface. */
    public enum Transport {
        /** Wi-Fi. */
        WIFI,
        /** Ethernet, including USB tethering adapters. */
        ETHERNET,
        /** Cellular data. */
        CELLULAR,
        /** Any VPN, including always-on and per-app tunnels. */
        VPN,
        /** Anything else, including Bluetooth and unknown transports. */
        OTHER
    }

    @NonNull
    private final String mName;

    @NonNull
    private final Inet4Address mAddress;

    private final int mPrefixLength;

    @NonNull
    private final Transport mTransport;

    private final boolean mPointToPoint;

    private final boolean mLoopback;

    /**
     * Creates an interface description.
     *
     * @param name OS interface name, for example {@code wlan0}
     * @param address local IPv4 address
     * @param prefixLength prefix length, {@code 0..32}
     * @param transport transport of the owning network
     * @param pointToPoint whether the interface is a point-to-point link
     * @param loopback whether the address is a loopback address
     * @throws IllegalArgumentException if the prefix is out of range
     */
    public LocalInterface(@NonNull final String name, @NonNull final Inet4Address address,
        final int prefixLength, @NonNull final Transport transport, final boolean pointToPoint,
        final boolean loopback) {
        if (prefixLength < 0 || prefixLength > 32) {
            throw new IllegalArgumentException("prefixLength out of range: " + prefixLength);
        }
        mName = Objects.requireNonNull(name, "name");
        mAddress = Objects.requireNonNull(address, "address");
        mPrefixLength = prefixLength;
        mTransport = Objects.requireNonNull(transport, "transport");
        mPointToPoint = pointToPoint;
        mLoopback = loopback;
    }

    /**
     * Creates an IPv4 interface description from literals, with no point-to-point or loopback
     * flags; tests and the enumerator use this to keep call sites short.
     *
     * @throws IllegalArgumentException if {@code address} is not an IPv4 literal
     */
    @NonNull
    public static LocalInterface ipv4(@NonNull final String name, @NonNull final String address,
        final int prefixLength, @NonNull final Transport transport) {
        return new LocalInterface(name, parse(address), prefixLength, transport, false, false);
    }

    /** Parses a numeric IPv4 literal without ever resolving a name. */
    @NonNull
    private static Inet4Address parse(@NonNull final String address) {
        final String[] parts = address.split("\\.", -1);
        if (parts.length != 4) {
            throw new IllegalArgumentException("not an IPv4 literal: " + address);
        }
        final byte[] bytes = new byte[4];
        for (int i = 0; i < parts.length; i++) {
            final int value;
            try {
                value = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("not an IPv4 literal: " + address, e);
            }
            if (value < 0 || value > 255) {
                throw new IllegalArgumentException("not an IPv4 literal: " + address);
            }
            bytes[i] = (byte) value;
        }
        try {
            return (Inet4Address) InetAddress.getByAddress(bytes);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("not an IPv4 literal: " + address, e);
        }
    }

    @NonNull
    public String getName() {
        return mName;
    }

    @NonNull
    public Inet4Address getAddress() {
        return mAddress;
    }

    public int getPrefixLength() {
        return mPrefixLength;
    }

    @NonNull
    public Transport getTransport() {
        return mTransport;
    }

    public boolean isPointToPoint() {
        return mPointToPoint;
    }

    public boolean isLoopback() {
        return mLoopback;
    }

    @Nullable
    @Override
    public String toString() {
        return "LocalInterface{"
            + "name='" + mName + '\''
            + ", address=" + mAddress.getHostAddress()
            + '/' + mPrefixLength
            + ", transport=" + mTransport
            + ", pointToPoint=" + mPointToPoint
            + ", loopback=" + mLoopback
            + '}';
    }
}
