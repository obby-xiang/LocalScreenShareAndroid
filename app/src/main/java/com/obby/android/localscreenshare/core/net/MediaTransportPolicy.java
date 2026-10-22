package com.obby.android.localscreenshare.core.net;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Pure decision that picks the media channel of a call, and the next one after a failure.
 *
 * <p>The decision is a table, not a heuristic: the client declares which transports it can speak,
 * the server decides what it is willing to offer, and both are filtered by the gates of the
 * deployment - the hidden {@code media_transport} setting, which pins the call to one channel for
 * A/B measurement, and the experimental datagram tier, which is off unless it is enabled. Nothing
 * here touches a socket or a clock, so every combination is testable as a value.</p>
 *
 * <p><b>Order.</b> The preferred order is datagrams first, then the dedicated stream, then the
 * control-plane carrier: {@code udp}, {@code tcp}, {@code grpc}. The control plane is always the
 * terminal candidate, because it is the carrier the call is already using, so a client that
 * declares nothing (including an old client that has never heard of the field) and a call whose
 * every offer failed both end up on it instead of being refused.</p>
 *
 * <p><b>No second try.</b> {@link #next} never returns the transport that just failed nor one the
 * caller already failed, so a broken channel cannot be offered twice within one call. That is what
 * keeps a repeated failure from looping the negotiation instead of falling back.</p>
 */
public final class MediaTransportPolicy {

    /**
     * Candidate order of the policy, best first; the control plane is the terminal fallback.
     */
    private static final MediaTransportKind[] PREFERENCE_ORDER = {
            MediaTransportKind.UDP,
            MediaTransportKind.TCP,
            MediaTransportKind.GRPC,
    };

    private MediaTransportPolicy() {
    }

    /**
     * Deployment-wide preference of the media path, from the hidden {@code media_transport} setting.
     */
    public enum Mode {

        /**
         * Let the server choose the best channel the client supports, falling back to the control
         * plane when nothing else works.
         */
        AUTO,

        /**
         * Pin every call to the control-plane carrier; the A/B baseline.
         */
        GRPC,

        /**
         * Prefer a dedicated stream, falling back to the control plane.
         */
        TCP,

        /**
         * Prefer datagrams; in this build that also enables the experimental tier.
         */
        UDP;

        /**
         * Parses the stored setting value.
         *
         * <p>Unknown or missing values resolve to {@link #AUTO}, so a downgrade or a hand-edited
         * preference can never pin a call to a channel the build cannot serve.</p>
         *
         * @param value stored value; null or blank resolves to {@code AUTO}
         */
        public static Mode parse(String value) {
            if (value == null) {
                return AUTO;
            }
            String trimmed = value.trim();
            for (Mode mode : values()) {
                if (mode.name().equalsIgnoreCase(trimmed)) {
                    return mode;
                }
            }
            return AUTO;
        }
    }

    /**
     * Returns the transports a deployment with {@code mode} is willing to offer.
     *
     * <p>The control plane is included in every answer except an empty set, and a pinned
     * {@link Mode#GRPC} offers nothing else, which is what makes it a clean baseline.</p>
     *
     * @param mode configured preference; null resolves to {@link Mode#AUTO}
     * @return an immutable set of candidates, in the policy's order
     */
    public static Set<MediaTransportKind> available(Mode mode) {
        Mode resolved = mode == null ? Mode.AUTO : mode;
        switch (resolved) {
            case GRPC:
                return Collections.unmodifiableSet(EnumSet.of(MediaTransportKind.GRPC));
            case TCP:
                return Collections.unmodifiableSet(
                        EnumSet.of(MediaTransportKind.TCP, MediaTransportKind.GRPC));
            case UDP:
                return Collections.unmodifiableSet(
                        EnumSet.of(MediaTransportKind.UDP, MediaTransportKind.GRPC));
            case AUTO:
            default:
                // Every channel the deployment can serve; the datagram tier stays gated by the
                // explicit switch, so {@code auto} prefers the dedicated stream.
                return Collections.unmodifiableSet(EnumSet.allOf(MediaTransportKind.class));
        }
    }

    /**
     * Returns whether the datagram tier may be offered.
     *
     * <p>The explicit settings win: {@code udp} turns the tier on, {@code tcp} and {@code grpc}
     * turn it off. The default {@code auto} follows the user-facing switch of the server, which is
     * off until the user opts into the experimental tier - so a device that never touches the
     * setting keeps the behaviour it had before the tier existed.</p>
     *
     * @param mode            configured preference; null resolves to {@link Mode#AUTO}
     * @param udpTierEnabled  whether the user turned the experimental tier on
     */
    public static boolean allowsUdp(Mode mode, boolean udpTierEnabled) {
        Mode resolved = mode == null ? Mode.AUTO : mode;
        if (resolved == Mode.UDP) {
            return true;
        }
        if (resolved == Mode.TCP || resolved == Mode.GRPC) {
            return false;
        }
        return udpTierEnabled;
    }

    /**
     * Chooses the first channel to offer.
     *
     * @param clientOffered  transport names the client declared; empty means control plane only
     * @param serverAvailable channels the deployment may offer
     * @param udpEnabled     whether the experimental datagram tier may be used
     * @return the channel to offer, or {@link MediaTransportKind#GRPC} when nothing else applies
     */
    public static MediaTransportKind first(Set<String> clientOffered,
                                          Set<MediaTransportKind> serverAvailable,
                                          boolean udpEnabled) {
        return choose(null, clientOffered, serverAvailable, udpEnabled, null);
    }

    /**
     * Chooses the channel to offer after one failed.
     *
     * @param failed         transport that just failed; skipped
     * @param clientOffered  transport names the client declared; empty means control plane only
     * @param serverAvailable channels the deployment may offer
     * @param udpEnabled     whether the experimental datagram tier may be used
     * @param alreadyFailed  transports that failed earlier in this call; skipped
     * @return the next channel, or {@link MediaTransportKind#GRPC} when nothing else applies
     */
    public static MediaTransportKind next(MediaTransportKind failed, Set<String> clientOffered,
                                         Set<MediaTransportKind> serverAvailable,
                                         boolean udpEnabled,
                                         Set<MediaTransportKind> alreadyFailed) {
        return choose(failed, clientOffered, serverAvailable, udpEnabled, alreadyFailed);
    }

    private static MediaTransportKind choose(MediaTransportKind failed,
                                             Set<String> clientOffered,
                                             Set<MediaTransportKind> serverAvailable,
                                             boolean udpEnabled,
                                             Set<MediaTransportKind> alreadyFailed) {
        Set<String> offered = normalize(clientOffered);
        if (offered.isEmpty()) {
            return MediaTransportKind.GRPC;
        }
        for (MediaTransportKind kind : PREFERENCE_ORDER) {
            if (kind == MediaTransportKind.UDP && !udpEnabled) {
                continue;
            }
            if (serverAvailable == null || !serverAvailable.contains(kind)) {
                continue;
            }
            if (kind == failed || (alreadyFailed != null && alreadyFailed.contains(kind))) {
                continue;
            }
            if (kind != MediaTransportKind.GRPC && !offered.contains(kind.getLabel())) {
                continue;
            }
            return kind;
        }
        return MediaTransportKind.GRPC;
    }

    /**
     * Lowercases and trims the declared names, so a peer that sends {@code TCP} still matches.
     */
    private static Set<String> normalize(Set<String> names) {
        if (names == null || names.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> normalized = new LinkedHashSet<>(names.size());
        for (String name : names) {
            if (name == null) {
                continue;
            }
            String trimmed = name.trim();
            if (!trimmed.isEmpty()) {
                normalized.add(trimmed.toLowerCase(Locale.ROOT));
            }
        }
        return normalized;
    }
}
