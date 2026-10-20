package com.obby.android.localscreenshare.discovery.store;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.obby.android.localscreenshare.discovery.model.AuthMode;
import com.obby.android.localscreenshare.discovery.model.DeviceAddress;
import com.obby.android.localscreenshare.discovery.model.DevicePresence;
import com.obby.android.localscreenshare.discovery.model.DeviceRole;
import com.obby.android.localscreenshare.discovery.model.DiscoveryChannel;
import com.obby.android.localscreenshare.discovery.model.JoinPolicy;
import com.obby.android.localscreenshare.discovery.model.ShareState;
import com.obby.android.localscreenshare.discovery.provider.DeviceObservation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mutable merge state of one device inside {@link DeviceStore}.
 *
 * <p>An entry collects what every channel reported: identity, advertised capabilities and one
 * address per channel/host/port. Fields that a provider did not report are left untouched, so a
 * sparse legacy advertisement never erases a richer one. The store owns all timing decisions;
 * this class only records timestamps.</p>
 *
 * <p>Not thread-safe; confined to the store, which is confined to the main thread.</p>
 */
final class DeviceEntry {

    @NonNull
    final String id;

    @Nullable
    String fingerprint;

    @Nullable
    String name;

    @NonNull
    DeviceRole role = DeviceRole.UNKNOWN;

    @NonNull
    ShareState state = ShareState.UNKNOWN;

    @NonNull
    JoinPolicy join = JoinPolicy.UNKNOWN;

    @NonNull
    AuthMode auth = AuthMode.UNKNOWN;

    boolean secure;

    boolean secureKnown;

    @NonNull
    String protocolVersion = "1.0";

    int metadataVersion;

    int capabilities;

    boolean favorite;

    boolean pinned;

    boolean identityChanged;

    boolean leaving;

    // True once any address was confirmed; lastConfirmedMs is only meaningful after that.
    boolean confirmed;

    long firstSeenMs;

    long lastSeenMs;

    long lastConfirmedMs;

    @Nullable
    String boot;

    @Nullable
    Long seq;

    @NonNull
    private final Map<String, DeviceAddress> addresses = new LinkedHashMap<>();

    DeviceEntry(@NonNull final String id, final long nowMs) {
        this.id = id;
        firstSeenMs = nowMs;
        lastSeenMs = nowMs;
    }

    /**
     * Applies one non-goodbye observation; returns false when it is an out-of-order duplicate.
     */
    boolean merge(@NonNull final DeviceObservation observation, final long observedAtMs) {
        if (isOutOfOrder(observation)) {
            return false;
        }

        if (observation.getFingerprint() != null) {
            if (fingerprint == null) {
                fingerprint = observation.getFingerprint();
            } else if (!fingerprint.equals(observation.getFingerprint())) {
                // Keep the established trust anchor and surface the conflict instead of
                // silently rebinding the device to a new identity.
                identityChanged = true;
            }
        }

        if (observation.getName() != null && !observation.getName().trim().isEmpty()) {
            name = observation.getName();
        }
        if (observation.getRole() != null) {
            role = observation.getRole();
        }
        if (observation.getState() != null) {
            state = observation.getState();
        }
        if (observation.getJoin() != null) {
            join = observation.getJoin();
        }
        if (observation.getAuth() != null) {
            auth = observation.getAuth();
        }
        if (observation.getSecure() != null) {
            secure = observation.getSecure();
            secureKnown = true;
        }
        if (observation.getProtocolVersion() != null) {
            protocolVersion = observation.getProtocolVersion();
        }
        if (observation.getMetadataVersion() != null) {
            metadataVersion = observation.getMetadataVersion();
        }
        if (observation.getCapabilities() != null) {
            capabilities = observation.getCapabilities();
        }

        if (observation.hasAddress()) {
            final DeviceAddress address = new DeviceAddress(observation.getHost(), observation.getPort(),
                observation.getSource(), false, 0L);
            addresses.put(address.getKey(), address);
        }

        lastSeenMs = Math.max(lastSeenMs, observedAtMs);
        leaving = false;
        return true;
    }

    /**
     * Marks the matching address as confirmed, inserting it when the channel never reported it.
     */
    void confirm(@NonNull final DiscoveryChannel channel, @NonNull final String host, final int port,
        final long confirmedAtMs) {
        final DeviceAddress address = new DeviceAddress(host, port, channel, true, confirmedAtMs);
        addresses.put(address.getKey(), address);
        confirmed = true;
        lastConfirmedMs = Math.max(lastConfirmedMs, confirmedAtMs);
        lastSeenMs = Math.max(lastSeenMs, confirmedAtMs);
        leaving = false;
    }

    /**
     * Drops the address observed by {@code channel} at {@code host:port}, if it is known.
     */
    boolean removeAddress(@NonNull final DiscoveryChannel channel, @NonNull final String host, final int port) {
        return addresses.remove(new DeviceAddress(host, port, channel).getKey()) != null;
    }

    /**
     * Returns the addresses as an immutable snapshot, ordered by usefulness.
     */
    @NonNull
    List<DeviceAddress> addresses() {
        final List<DeviceAddress> snapshot = new ArrayList<>(addresses.values());
        Collections.sort(snapshot);
        return Collections.unmodifiableList(snapshot);
    }

    /**
     * True when the entry has no address and no channel reported it recently enough to keep.
     */
    boolean isExpired(final long nowMs, final long heardTtlMs, final long confirmedTtlMs,
        final long removeAfterMs) {
        if (favorite || pinned) {
            return false;
        }
        if (confirmed && nowMs - lastConfirmedMs <= confirmedTtlMs) {
            return false;
        }
        return nowMs - lastSeenMs > removeAfterMs;
    }

    /**
     * Derives the presence of this entry at {@code nowMs}; never returns null.
     */
    @NonNull
    DevicePresence presence(final long nowMs, final long confirmedTtlMs, final long heardTtlMs,
        final long removeAfterMs) {
        if (leaving) {
            return favorite || pinned ? DevicePresence.UNREACHABLE : DevicePresence.STALE;
        }
        if (confirmed && nowMs - lastConfirmedMs <= confirmedTtlMs) {
            return DevicePresence.CONFIRMED;
        }
        final long lastActivityMs = Math.max(lastSeenMs, lastConfirmedMs);
        if (nowMs - lastActivityMs <= heardTtlMs) {
            return DevicePresence.HEARD;
        }
        if (nowMs - lastActivityMs <= removeAfterMs) {
            return DevicePresence.STALE;
        }
        return favorite || pinned ? DevicePresence.UNREACHABLE : DevicePresence.STALE;
    }

    private boolean isOutOfOrder(@NonNull final DeviceObservation observation) {
        if (observation.getBoot() == null || observation.getSeq() == null) {
            return false;
        }
        if (boot == null) {
            boot = observation.getBoot();
            seq = observation.getSeq();
            return false;
        }
        if (!boot.equals(observation.getBoot())) {
            // A different boot means the peer restarted; its sequence baseline restarts too.
            boot = observation.getBoot();
            seq = observation.getSeq();
            return false;
        }
        if (observation.getSeq() <= seq) {
            return true;
        }
        seq = observation.getSeq();
        return false;
    }
}
