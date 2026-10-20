package com.obby.android.localscreenshare.settings;

import androidx.annotation.NonNull;


import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import com.obby.android.localscreenshare.discovery.nsd.NsdConstants;
import com.obby.android.localscreenshare.discovery.register.RegisterConstants;
import com.obby.android.localscreenshare.discovery.udp.MulticastConstants;

/**
 * Immutable snapshot of the user's discovery settings.
 *
 * <p>The snapshot is built from persisted values once per provider start, so a provider never
 * observes a half-applied setting. Values that fail validation fall back to the default instead of
 * disabling discovery: a mistyped group address must not make the device invisible without an
 * obvious reason.</p>
 *
 * <p><b>Tiers instead of numbers.</b> The Register deadline and the heartbeat periods move
 * together, because a shorter deadline without a faster heartbeat only adds failed handshakes.
 * {@link RefreshTier} exposes three presets; arbitrary values are deliberately not configurable.
 * The heartbeat periods feed the server announcement, the Register deadline feeds the client
 * handshake.</p>
 *
 * <p><b>Compatibility mode.</b> {@link #isCompatOnly()} restricts the device to mDNS: only the
 * legacy NSD service type is registered and discovered, the multicast channel and the address
 * scan are skipped, and the advertised capability bitmap drops JPEG so peers pick the H.264
 * baseline. It exists for networks where multicast is filtered and for legacy peers.</p>
 */
public final class DiscoverySettings {

    /**
     * Lowest accepted multicast port.
     */
    public static final int MIN_MULTICAST_PORT = 1024;

    /**
     * Highest accepted multicast port.
     */
    public static final int MAX_MULTICAST_PORT = 65535;

    /**
     * Lower bound of the administratively scoped multicast range (224.0.0.0/4).
     */
    private static final int MULTICAST_FIRST_OCTET_MIN = 224;

    /**
     * Upper bound of the administratively scoped multicast range (224.0.0.0/4).
     */
    private static final int MULTICAST_FIRST_OCTET_MAX = 239;

    /**
     * Number of octets in an IPv4 literal.
     */
    private static final int IPV4_OCTETS = 4;

    /**
     * Highest value of one IPv4 octet.
     */
    private static final int MAX_OCTET = 255;

    /**
     * Preset refresh cadence.
     *
     * <p>{@link #getRegisterTimeoutMs()} is the deadline of one Register call,
     * {@link #getHeartbeatActiveMs()} and {@link #getHeartbeatIdleMs()} are the announcement
     * periods while a capture session is live and while the server is idle.</p>
     */
    public enum RefreshTier {
        LOW_LATENCY("low_latency", 250L, 3_000L, 10_000L),
        BALANCED("balanced", RegisterConstants.REGISTER_TIMEOUT_MS,
            MulticastConstants.MULTICAST_HEARTBEAT_ACTIVE_MS, MulticastConstants.MULTICAST_HEARTBEAT_IDLE_MS),
        POWER_SAVER("power_saver", 1_000L, 10_000L, 30_000L);

        @NonNull
        private final String mWire;

        private final long mRegisterTimeoutMs;

        private final long mHeartbeatActiveMs;

        private final long mHeartbeatIdleMs;

        RefreshTier(@NonNull final String wire, final long registerTimeoutMs,
            final long heartbeatActiveMs, final long heartbeatIdleMs) {
            mWire = wire;
            mRegisterTimeoutMs = registerTimeoutMs;
            mHeartbeatActiveMs = heartbeatActiveMs;
            mHeartbeatIdleMs = heartbeatIdleMs;
        }

        /**
         * Returns the persisted wire value.
         */
        @NonNull
        public String wire() {
            return mWire;
        }

        /**
         * Returns the Register deadline of this preset.
         */
        public long getRegisterTimeoutMs() {
            return mRegisterTimeoutMs;
        }

        /**
         * Returns the announcement period while a capture session is live.
         */
        public long getHeartbeatActiveMs() {
            return mHeartbeatActiveMs;
        }

        /**
         * Returns the announcement period while the server is idle.
         */
        public long getHeartbeatIdleMs() {
            return mHeartbeatIdleMs;
        }

        /**
         * Parses a persisted wire value, accepting the enum name as well.
         *
         * @return the preset, or {@link #BALANCED} for an absent or unknown value
         */
        @NonNull
        public static RefreshTier fromWire(@NonNull final String wire) {
            final String normalized = wire.trim().toLowerCase(Locale.ROOT);
            for (final RefreshTier tier : values()) {
                if (tier.mWire.equals(normalized)
                    || tier.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                    return tier;
                }
            }
            return BALANCED;
        }
    }

    private final boolean mMulticastEnabled;

    @NonNull
    private final String mMulticastGroup;

    private final int mMulticastPort;

    @NonNull
    private final RefreshTier mRefreshTier;

    @NonNull
    private final VisibilityPolicy.Visibility mVisibility;

    private final long mVisibilityExpiresAtMs;

    private final boolean mCompatOnly;

    private DiscoverySettings(final boolean multicastEnabled, @NonNull final String multicastGroup,
        final int multicastPort, @NonNull final RefreshTier refreshTier,
        @NonNull final VisibilityPolicy.Visibility visibility, final long visibilityExpiresAtMs,
        final boolean compatOnly) {
        mMulticastEnabled = multicastEnabled;
        mMulticastGroup = multicastGroup;
        mMulticastPort = multicastPort;
        mRefreshTier = refreshTier;
        mVisibility = visibility;
        mVisibilityExpiresAtMs = visibilityExpiresAtMs;
        mCompatOnly = compatOnly;
    }

    /**
     * Returns the default settings: multicast on, visible, balanced refresh, mDNS and multicast.
     */
    @NonNull
    public static DiscoverySettings defaults() {
        return new DiscoverySettings(true, MulticastConstants.MULTICAST_GROUP, MulticastConstants.MULTICAST_PORT,
            RefreshTier.BALANCED, VisibilityPolicy.Visibility.VISIBLE, 0L, false);
    }

    /**
     * Builds a validated snapshot without a persisted visibility window.
     *
     * <p>Used by tests and by callers that do not read the persisted expiry. A temporary visibility
     * preset starts its window at the time it is evaluated; see
     * {@link #of(boolean, String, int, String, String, long, boolean)} for the persisted form.</p>
     */
    @NonNull
    public static DiscoverySettings of(final boolean multicastEnabled, final String multicastGroup,
        final int multicastPort, final String refreshTier, final String visibility,
        final boolean compatOnly) {
        return of(multicastEnabled, multicastGroup, multicastPort, refreshTier, visibility, 0L,
            compatOnly);
    }

    /**
     * Builds a validated snapshot.
     *
     * <p>An invalid group address, an out-of-range port or an unknown preset falls back to the
     * default for that field; the other fields keep the supplied values.</p>
     *
     * @param visibilityExpiresAtMs absolute expiry of the temporary preset, or {@code 0} when the
     *     preset has no window yet
     */
    @NonNull
    public static DiscoverySettings of(final boolean multicastEnabled, final String multicastGroup,
        final int multicastPort, final String refreshTier, final String visibility,
        final long visibilityExpiresAtMs, final boolean compatOnly) {
        return new DiscoverySettings(multicastEnabled, normalizeGroup(multicastGroup),
            normalizePort(multicastPort), RefreshTier.fromWire(refreshTier == null ? "" : refreshTier),
            VisibilityPolicy.Visibility.fromWire(visibility == null ? "" : visibility),
            Math.max(visibilityExpiresAtMs, 0L), compatOnly);
    }

    /**
     * Returns whether the supplied text is an IPv4 multicast group literal in 224.0.0.0/4.
     *
     * <p>Only dotted-quad literals are accepted; a host name would need a DNS lookup, which a
     * settings getter must not perform.</p>
     */
    public static boolean isValidGroupAddress(final String group) {
        if (group == null) {
            return false;
        }
        final String[] parts = group.trim().split("\\.", -1);
        if (parts.length != IPV4_OCTETS) {
            return false;
        }
        final int[] octets = new int[IPV4_OCTETS];
        for (int i = 0; i < IPV4_OCTETS; i++) {
            final String part = parts[i];
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int j = 0; j < part.length(); j++) {
                if (!Character.isDigit(part.charAt(j))) {
                    return false;
                }
            }
            octets[i] = Integer.parseInt(part);
            if (octets[i] > MAX_OCTET) {
                return false;
            }
        }
        return octets[0] >= MULTICAST_FIRST_OCTET_MIN && octets[0] <= MULTICAST_FIRST_OCTET_MAX;
    }

    /**
     * Returns whether the supplied port is inside the accepted multicast range.
     */
    public static boolean isValidPort(final int port) {
        return port >= MIN_MULTICAST_PORT && port <= MAX_MULTICAST_PORT;
    }

    /**
     * Returns whether the user enabled the multicast channel (announcement and reception).
     */
    public boolean isMulticastEnabled() {
        return mMulticastEnabled;
    }

    @NonNull
    public String getMulticastGroup() {
        return mMulticastGroup;
    }

    public int getMulticastPort() {
        return mMulticastPort;
    }

    @NonNull
    public RefreshTier getRefreshTier() {
        return mRefreshTier;
    }

    @NonNull
    public VisibilityPolicy.Visibility getVisibility() {
        return mVisibility;
    }

    /**
     * Returns the persisted expiry of the temporary preset, or {@code 0} when there is none.
     */
    public long getVisibilityExpiresAtMs() {
        return mVisibilityExpiresAtMs;
    }

    /**
     * Returns whether the device is restricted to mDNS discovery and registration.
     */
    public boolean isCompatOnly() {
        return mCompatOnly;
    }

    /**
     * Returns whether the multicast provider should run; compatibility mode always disables it.
     */
    public boolean isMulticastChannelEnabled() {
        return mMulticastEnabled && !mCompatOnly;
    }

    /**
     * Returns whether the address scan may run; compatibility mode never scans.
     */
    public boolean isScanEnabled() {
        return !mCompatOnly;
    }

    /**
     * Returns the NSD service types to discover and register.
     *
     * <p>Compatibility mode only uses the legacy type so a legacy peer sees this device and this
     * device sees legacy servers; the current type is added otherwise.</p>
     */
    @NonNull
    public List<String> nsdServiceTypes() {
        if (mCompatOnly) {
            return Collections.singletonList(NsdConstants.NSD_SERVICE_TYPE_LEGACY);
        }
        return Collections.unmodifiableList(
            Arrays.asList(NsdConstants.NSD_SERVICE_TYPE, NsdConstants.NSD_SERVICE_TYPE_LEGACY));
    }

    /**
     * Returns the capability bitmap to advertise for this mode.
     */
    public int capabilities() {
        return mCompatOnly
            ? NsdConstants.DEVICE_CAPABILITY_H264
            : NsdConstants.DEVICE_CAPABILITY_H264 | NsdConstants.DEVICE_CAPABILITY_JPEG;
    }

    /**
     * Returns the visibility policy of this snapshot, anchored at the persisted window.
     */
    @NonNull
    public VisibilityPolicy visibilityPolicy(final long nowMs) {
        if (mVisibility == VisibilityPolicy.Visibility.VISIBLE_10_MIN && mVisibilityExpiresAtMs > 0L) {
            return VisibilityPolicy.of(mVisibility, mVisibilityExpiresAtMs);
        }
        return VisibilityPolicy.forVisibility(mVisibility, nowMs);
    }

    @NonNull
    private static String normalizeGroup(final String group) {
        return isValidGroupAddress(group) ? group.trim() : MulticastConstants.MULTICAST_GROUP;
    }

    private static int normalizePort(final int port) {
        return isValidPort(port) ? port : MulticastConstants.MULTICAST_PORT;
    }

    @Override
    public String toString() {
        return "DiscoverySettings{multicastEnabled=" + mMulticastEnabled
            + ", multicastGroup='" + mMulticastGroup + '\''
            + ", multicastPort=" + mMulticastPort
            + ", refreshTier=" + mRefreshTier
            + ", visibility=" + mVisibility
            + ", compatOnly=" + mCompatOnly
            + '}';
    }
}
