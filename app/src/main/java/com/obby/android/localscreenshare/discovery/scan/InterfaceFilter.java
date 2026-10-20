package com.obby.android.localscreenshare.discovery.scan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides which local interfaces a LAN scan may touch.
 *
 * <p>Scanning a VPN or a cellular link is either useless (the peer is not on that link) or
 * harmful (it bills traffic and exposes the scan to the carrier). Point-to-point links have one
 * peer, so enumerating them only produces noise; loopback never carries a peer. Interfaces whose
 * name belongs to a tunnel, modem or virtual adapter are dropped even when the transport looks
 * acceptable, because the transport of a VPN can also report {@code WIFI}.</p>
 *
 * <p>Pure and deterministic, so the rules are covered by JVM tests.</p>
 */
public final class InterfaceFilter {

    /** Interface-name prefixes that never carry a LAN peer worth scanning. */
    private static final Set<String> EXCLUDED_NAME_PREFIXES = new HashSet<>();

    static {
        Collections.addAll(EXCLUDED_NAME_PREFIXES, "tun", "ppp", "rmnet", "dummy", "p2p");
    }

    private InterfaceFilter() {
    }

    /** Returns whether one interface may be scanned. */
    public static boolean isEligible(@NonNull final LocalInterface candidate) {
        if (candidate.isLoopback() || candidate.isPointToPoint()) {
            return false;
        }
        if (candidate.getTransport() != LocalInterface.Transport.WIFI
            && candidate.getTransport() != LocalInterface.Transport.ETHERNET) {
            return false;
        }
        return !hasExcludedName(candidate.getName());
    }

    /** Returns the eligible interfaces, preserving the input order. */
    @NonNull
    public static List<LocalInterface> eligible(@NonNull final List<LocalInterface> candidates) {
        final List<LocalInterface> eligible = new ArrayList<>(candidates.size());
        for (final LocalInterface candidate : candidates) {
            if (isEligible(candidate)) {
                eligible.add(candidate);
            }
        }
        return Collections.unmodifiableList(eligible);
    }

    /**
     * Returns whether the interface name starts with a prefix that marks a tunnel, modem or
     * virtual adapter. A null or blank name is treated as excluded, because the platform could
     * not identify the interface and the scan must not guess.
     */
    public static boolean hasExcludedName(@Nullable final String name) {
        if (name == null || name.trim().isEmpty()) {
            return true;
        }
        final String normalized = name.trim().toLowerCase(Locale.ROOT);
        for (final String prefix : EXCLUDED_NAME_PREFIXES) {
            if (normalized.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
