package com.obby.android.localscreenshare.discovery.udp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.obby.android.localscreenshare.discovery.model.AuthMode;
import com.obby.android.localscreenshare.discovery.model.DeviceRole;
import com.obby.android.localscreenshare.discovery.model.DiscoveryChannel;
import com.obby.android.localscreenshare.discovery.model.JoinPolicy;
import com.obby.android.localscreenshare.discovery.model.DeviceInfo;
import com.obby.android.localscreenshare.discovery.model.ShareState;
import com.obby.android.localscreenshare.discovery.provider.DeviceObservation;

import java.util.Objects;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * One multicast announcement: the discovery-layer minimum a device broadcasts on the local link.
 *
 * <p>It carries no fingerprint, no device name unless the user opted in, and no secret. The
 * {@code boot} and {@code seq} pair orders announcements from one process run without a clock,
 * {@code ttl} states how long the sender considers the announcement fresh, and
 * {@code announces=false} is a goodbye.</p>
 *
 * <p>Instances are immutable. {@link #toObservation(String, long)} converts an accepted
 * announcement into a store observation; announcements can never produce a confirmation.</p>
 */
@Getter
@ToString
public final class Announcement {

    /** Schema version understood by this implementation. */
    public static final int VERSION = 1;

    /** Maximum accepted length of every string field, in characters. */
    public static final int MAX_STRING_LENGTH = 64;

    private final int version;

    @NonNull
    private final String id;

    @Nullable
    private final String name;

    @NonNull
    private final DeviceRole role;

    @NonNull
    private final ShareState state;

    @NonNull
    private final JoinPolicy join;

    private final boolean secure;

    @NonNull
    private final AuthMode auth;

    @NonNull
    private final String protocolVersion;

    private final int metadataVersion;

    private final int capabilities;

    /** gRPC port the sender accepts Register and stream calls on; 0 when it serves nothing. */
    private final int port;

    @Nullable
    private final String platform;

    /** Random per-start identifier; null when the sender did not provide one. */
    @Nullable
    private final String boot;

    /** Monotonic sequence within one {@link #getBoot() boot}; negative when unknown. */
    private final long seq;

    /** Seconds the sender considers this announcement fresh. */
    private final int ttlSeconds;

    /** False for a goodbye. */
    private final boolean announces;

    /**
     * Creates an announcement.
     *
     * @throws IllegalArgumentException if the id is blank or longer than
     *     {@link #MAX_STRING_LENGTH}, or the port is out of range
     */
    @Builder(builderMethodName = "builder", toBuilder = true)
    private Announcement(final int version, final String id, final String name, final DeviceRole role,
        final ShareState state, final JoinPolicy join, final boolean secure, final AuthMode auth,
        final String protocolVersion, final int metadataVersion, final int capabilities, final int port,
        final String platform, final String boot, final long seq, final int ttlSeconds,
        final boolean announces) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (id.length() > MAX_STRING_LENGTH) {
            throw new IllegalArgumentException("id must be at most " + MAX_STRING_LENGTH + " characters");
        }
        if (port < 0 || port > 0xFFFF) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        this.version = version == 0 ? VERSION : version;
        this.id = id;
        this.name = name == null || name.trim().isEmpty() ? null : name;
        this.role = role == null ? DeviceRole.UNKNOWN : role;
        this.state = state == null ? ShareState.UNKNOWN : state;
        this.join = join == null ? JoinPolicy.UNKNOWN : join;
        this.secure = secure;
        this.auth = auth == null ? AuthMode.UNKNOWN : auth;
        this.protocolVersion = protocolVersion == null || protocolVersion.trim().isEmpty()
            ? DeviceInfo.DEFAULT_PROTOCOL_VERSION : protocolVersion;
        this.metadataVersion = metadataVersion;
        this.capabilities = capabilities;
        this.port = port;
        this.platform = platform == null || platform.trim().isEmpty() ? null : platform;
        this.boot = boot == null || boot.trim().isEmpty() ? null : boot;
        this.seq = seq;
        this.ttlSeconds = Math.max(0, ttlSeconds);
        this.announces = announces;
    }

    /** True when this announcement says the sender is leaving. */
    public boolean isGoodbye() {
        return !announces;
    }

    /**
     * Converts this announcement, received from {@code host}, into a store observation.
     *
     * <p>The observation is a sighting only: it never confirms reachability. The port is omitted
     * when the sender serves nothing, so an entry without an address can still exist.</p>
     */
    @NonNull
    public DeviceObservation toObservation(@NonNull final String host, final long nowMs) {
        return DeviceObservation.builder()
            .source(DiscoveryChannel.MULTICAST)
            .id(id)
            .name(name)
            .role(role)
            .state(state)
            .join(join)
            .secure(secure)
            .auth(auth)
            .protocolVersion(protocolVersion)
            .metadataVersion(metadataVersion)
            .capabilities(capabilities)
            .host(host)
            .port(port > 0 ? port : null)
            .boot(boot)
            .seq(boot == null ? null : seq)
            .lastSeenMs(nowMs)
            .goodbye(isGoodbye())
            .build();
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Announcement)) {
            return false;
        }
        final Announcement that = (Announcement) o;
        return version == that.version && secure == that.secure && metadataVersion == that.metadataVersion
            && capabilities == that.capabilities && port == that.port && seq == that.seq
            && ttlSeconds == that.ttlSeconds && announces == that.announces && id.equals(that.id)
            && Objects.equals(name, that.name) && role == that.role && state == that.state
            && join == that.join && auth == that.auth && protocolVersion.equals(that.protocolVersion)
            && Objects.equals(platform, that.platform) && Objects.equals(boot, that.boot);
    }

    @Override
    public int hashCode() {
        return Objects.hash(version, id, name, role, state, join, secure, auth, protocolVersion,
            metadataVersion, capabilities, port, platform, boot, seq, ttlSeconds, announces);
    }
}
