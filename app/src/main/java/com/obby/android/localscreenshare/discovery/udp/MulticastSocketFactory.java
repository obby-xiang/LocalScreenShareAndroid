package com.obby.android.localscreenshare.discovery.udp;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.List;

/**
 * Opens one multicast endpoint per usable network interface.
 *
 * <p>An interface that cannot join the group — no IPv4 address, multicast unsupported, or a
 * bind/join failure — is skipped instead of failing the whole channel, because a phone commonly
 * has a VPN or a dormant cellular interface that cannot carry link-local multicast. The returned
 * list is empty when no interface qualified.</p>
 */
@FunctionalInterface
public interface MulticastSocketFactory {

    /**
     * Opens and joins the configured group on every usable interface.
     *
     * @return the opened endpoints; never null, possibly empty
     * @throws IOException if the group address cannot be resolved
     */
    @NonNull
    List<MulticastEndpoint> open(@NonNull MulticastConfig config) throws IOException;
}
