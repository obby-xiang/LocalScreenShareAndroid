package com.obby.android.localscreenshare.discovery.manual;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.obby.android.localscreenshare.discovery.model.DiscoveryChannel;
import com.obby.android.localscreenshare.discovery.provider.DeviceObservation;

/**
 * Turns one stored manual entry into the observation that seeds the device list after a restart.
 *
 * <p><b>Why the seed matters.</b> A manual entry is the only device the process knows without any
 * provider reporting it, so at start the list is rebuilt from storage alone. If that seed is poorer
 * than what the confirming handshake saw, the row is visibly wrong until the next periodic re-probe:
 * the connect gate reads the same row, so the user is told the peer does not support encryption and
 * the tap is refused. The seed therefore has to carry every field the entry already proves.</p>
 *
 * <p><b>{@code secure} semantics.</b> The flag is the peer's <i>transport capability</i> - whether it
 * can be dialled over TLS - not the "private screen" setting and not "seen in this cycle" (see
 * DB-SEC-002). Announcements fill it from their advertisement and the confirming handshake fills it
 * from the response, so it describes the peer, not the current sighting.</p>
 *
 * <p><b>Restoring it.</b> A stored fingerprint can only come from the responder's certificate,
 * because that is the only place the client reads it: the probe that persisted the entry completed a
 * TLS handshake. That is the same evidence the live confirmation path writes, so a fingerprinted
 * entry seeds {@code secure = true} and keeps its row connectable across a restart. An entry without
 * a fingerprint (a document written before the field was the gate, or a plaintext-mode probe) proves
 * nothing about TLS: the seed then leaves {@code secure} <b>null</b>, the row keeps reporting an
 * unencrypted peer and the gate keeps refusing it. The rule never turns "unknown" into "secure" -
 * a later announcement or handshake is what promotes such a row.</p>
 *
 * <p>The stored address is still only a hint: the seed does not mark the device confirmed or
 * reachable, and the periodic re-probe corrects the row as soon as the peer answers (or reports it
 * unreachable).</p>
 */
public final class ManualObservationMapper {

    private ManualObservationMapper() {
    }

    /**
     * Returns the observation that seeds {@code device} into the device store at start.
     *
     * @param device stored manual entry; required
     * @return an immutable observation; never null
     */
    @NonNull
    public static DeviceObservation toSeedObservation(@NonNull final ManualDevice device) {
        if (device == null) {
            throw new IllegalArgumentException("device must not be null");
        }
        return DeviceObservation.builder()
            .source(DiscoveryChannel.MANUAL)
            .id(device.getId())
            .fingerprint(device.getFingerprint())
            .name(device.getName())
            .secure(secureClaim(device.getFingerprint()))
            .host(device.getHost())
            .port(device.getPort())
            .build();
    }

    /**
     * Returns the transport claim a stored identity supports: true for a fingerprint, null otherwise.
     *
     * <p>Public so the favorite seed can apply the identical rule without a second copy of the
     * reasoning; the seed of a favorite has the same provenance as the seed of a manual entry.</p>
     *
     * @param fingerprint stored fingerprint, or null when the entry never completed a handshake
     * @return {@link Boolean#TRUE} for a fingerprinted entry, null when no claim can be made
     */
    @Nullable
    public static Boolean secureClaim(@Nullable final String fingerprint) {
        return fingerprint == null || fingerprint.trim().isEmpty() ? null : Boolean.TRUE;
    }
}
