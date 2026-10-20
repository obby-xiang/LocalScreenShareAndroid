package com.obby.android.localscreenshare.discovery.provider;

import androidx.annotation.NonNull;

import com.obby.android.localscreenshare.discovery.model.AuthMode;
import com.obby.android.localscreenshare.discovery.model.DeviceRole;
import com.obby.android.localscreenshare.discovery.model.DiscoveryChannel;
import com.obby.android.localscreenshare.discovery.model.JoinPolicy;
import com.obby.android.localscreenshare.discovery.model.ShareState;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * One sighting of a device by one discovery provider.
 *
 * <p>An observation is a hint, never a truth: providers fill in what they managed to read and
 * leave everything else null, so a legacy advertisement without role, state or name still yields
 * an observation. Providers can never mark a device reachable — confirmation is an application
 * decision and is applied through the store, not through this type.</p>
 *
 * <p>{@link #isLost()} reports the disappearance of one address from the channel that saw it;
 * {@link #isGoodbye()} reports that the device announced it is leaving. Neither is a confirmation
 * and neither removes a favorite.</p>
 */
@Getter
@ToString
public final class DeviceObservation {

    @NonNull
    private final DiscoveryChannel source;

    /** Stable device id, or null when the provider could not read an id at all. */
    private final String id;

    private final String fingerprint;

    private final String name;

    private final DeviceRole role;

    private final ShareState state;

    private final JoinPolicy join;

    /** Secure-transport flag; null when the provider did not advertise it. */
    private final Boolean secure;

    private final AuthMode auth;

    private final String protocolVersion;

    private final Integer metadataVersion;

    private final Integer capabilities;

    private final String host;

    private final Integer port;

    /** Random per-start identifier used to discard out-of-order announcements. */
    private final String boot;

    /** Monotonic sequence within one {@link #getBoot() boot}. */
    private final Long seq;

    /** Observation time in milliseconds; null means "use the store clock". */
    private final Long lastSeenMs;

    private final boolean lost;

    private final boolean goodbye;

    @Builder(builderMethodName = "builder")
    private DeviceObservation(@NonNull final DiscoveryChannel source, final String id, final String fingerprint,
        final String name, final DeviceRole role, final ShareState state, final JoinPolicy join,
        final Boolean secure, final AuthMode auth, final String protocolVersion, final Integer metadataVersion,
        final Integer capabilities, final String host, final Integer port, final String boot, final Long seq,
        final Long lastSeenMs, final boolean lost, final boolean goodbye) {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        this.source = source;
        this.id = id;
        this.fingerprint = fingerprint;
        this.name = name;
        this.role = role;
        this.state = state;
        this.join = join;
        this.secure = secure;
        this.auth = auth;
        this.protocolVersion = protocolVersion;
        this.metadataVersion = metadataVersion;
        this.capabilities = capabilities;
        this.host = host;
        this.port = port;
        this.boot = boot;
        this.seq = seq;
        this.lastSeenMs = lastSeenMs;
        this.lost = lost;
        this.goodbye = goodbye;
    }

    /** True when this observation carries a complete address. */
    public boolean hasAddress() {
        return host != null && !host.trim().isEmpty() && port != null;
    }

    /** True when the observation can be merged into a device entry. */
    public boolean hasIdentity() {
        return id != null && !id.trim().isEmpty();
    }
}
