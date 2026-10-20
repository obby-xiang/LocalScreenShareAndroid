package com.obby.android.localscreenshare.discovery.scan;

import androidx.annotation.NonNull;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Host candidates derived from one local IPv4 address and its prefix length.
 *
 * <p>The scan never assumes a {@code /24}. It computes the network and broadcast addresses from
 * the local address and the mask, then enumerates the usable hosts in between, skipping the local
 * address, the network address and the broadcast address. A prefix with fewer than 22 network bits
 * describes more than {@link #MAX_HOSTS} hosts; rather than scanning a {@code /16} the caller gets
 * the 254 hosts of the local {@code /24} and a {@code partial} flag, so the caller can say the
 * result is not exhaustive.</p>
 *
 * <p>Pure JVM: no Android dependency, deterministic ascending order, every address is created from
 * its four bytes so no name resolution is ever attempted.</p>
 */
public final class NetworkPrefix {

    /**
     * Upper bound of candidates one interface contributes.
     */
    public static final int MAX_HOSTS = 1024;

    /**
     * Prefix length whose network holds more than {@link #MAX_HOSTS} usable hosts.
     */
    public static final int MAX_FULL_PREFIX_LENGTH = 22;

    private static final int LOCAL_NETWORK_MASK = 0xFFFFFF00;

    private NetworkPrefix() {
    }

    /**
     * Returns the candidates of one interface with the default {@link #MAX_HOSTS} bound.
     *
     * @param local local IPv4 address of the interface
     * @param prefixLength prefix length of the interface, {@code 0..32}
     */
    @NonNull
    public static Result candidates(@NonNull final Inet4Address local, final int prefixLength) {
        return candidates(local, prefixLength, MAX_HOSTS);
    }

    /**
     * Returns the candidates of one interface.
     *
     * <p>A {@code /31} or {@code /32} has no usable host and yields an empty, non-partial result.
     * A network larger than {@code maxHosts} yields the local {@code /24} minus the local address
     * and a partial flag.</p>
     *
     * @param local local IPv4 address of the interface
     * @param prefixLength prefix length of the interface, {@code 0..32}
     * @param maxHosts maximum number of candidates to generate
     * @throws IllegalArgumentException if the prefix length or the bound is out of range
     */
    @NonNull
    public static Result candidates(@NonNull final Inet4Address local, final int prefixLength,
        final int maxHosts) {
        Objects.requireNonNull(local, "local");
        if (prefixLength < 0 || prefixLength > 32) {
            throw new IllegalArgumentException("prefixLength out of range: " + prefixLength);
        }
        if (maxHosts <= 0) {
            throw new IllegalArgumentException("maxHosts must be positive: " + maxHosts);
        }

        final int localValue = toInt(local);
        final int mask = mask(prefixLength);
        final long network = localValue & mask & 0xFFFFFFFFL;
        final long broadcast = network | ((~mask) & 0xFFFFFFFFL);
        long first = network + 1;
        long last = broadcast - 1;
        boolean partial = false;

        if (last < first) {
            return new Result(Collections.emptyList(), false);
        }

        if (last - first + 1 > maxHosts) {
            partial = true;
            final long localNetwork = localValue & LOCAL_NETWORK_MASK & 0xFFFFFFFFL;
            first = localNetwork + 1;
            last = localNetwork + 254;
        }

        final List<Inet4Address> addresses = new ArrayList<>((int) Math.min(last - first + 1, maxHosts));
        for (long value = first; value <= last && addresses.size() < maxHosts; value++) {
            if (value == (localValue & 0xFFFFFFFFL)) {
                continue;
            }
            addresses.add(toAddress((int) value));
        }
        return new Result(addresses, partial);
    }

    /**
     * Builds the network mask of a prefix length.
     */
    private static int mask(final int prefixLength) {
        return prefixLength == 0 ? 0 : 0xFFFFFFFF << (32 - prefixLength);
    }

    /**
     * Reads the four address bytes as an unsigned-looking int.
     */
    private static int toInt(@NonNull final Inet4Address address) {
        final byte[] bytes = address.getAddress();
        return ((bytes[0] & 0xFF) << 24) | ((bytes[1] & 0xFF) << 16)
            | ((bytes[2] & 0xFF) << 8) | (bytes[3] & 0xFF);
    }

    /**
     * Creates a literal address from four bytes; never resolves a name.
     */
    @NonNull
    private static Inet4Address toAddress(final int value) {
        final byte[] bytes = {
            (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value
        };
        try {
            return (Inet4Address) InetAddress.getByAddress(bytes);
        } catch (UnknownHostException e) {
            // Four bytes always describe an address; this cannot happen.
            throw new IllegalStateException("unable to build an IPv4 literal", e);
        }
    }

    /**
     * Candidates of one interface plus whether the enumeration had to be truncated.
     */
    public static final class Result {

        @NonNull
        private final List<Inet4Address> mAddresses;

        private final boolean mPartial;

        private Result(@NonNull final List<Inet4Address> addresses, final boolean partial) {
            mAddresses = Collections.unmodifiableList(addresses);
            mPartial = partial;
        }

        /**
         * Returns the candidates in ascending host order, never including the local address.
         */
        @NonNull
        public List<Inet4Address> getAddresses() {
            return mAddresses;
        }

        /**
         * True when the network was larger than the bound and only the local {@code /24} was used.
         */
        public boolean isPartial() {
            return mPartial;
        }

        /**
         * Returns the number of candidates.
         */
        public int size() {
            return mAddresses.size();
        }
    }
}
