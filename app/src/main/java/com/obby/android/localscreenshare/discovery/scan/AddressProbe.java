package com.obby.android.localscreenshare.discovery.scan;

import androidx.annotation.NonNull;

/**
 * One single-shot Register probe against one address.
 *
 * <p>The scanner and the manual-address provider need different deadlines but the same shape: ask
 * one address once, get either the peer summary or a failure. The interface keeps gRPC out of the
 * scheduling code and lets tests count calls, bound concurrency and synthesise timeouts.</p>
 */
public interface AddressProbe {

    /**
     * Probes one address and blocks until it is answered or the deadline elapses.
     *
     * <p>Implementations must return instead of throwing; a probe failure is a
     * {@link ProbeResult#failed(String, int, String)} value. The call is expected to run on a
     * worker thread.</p>
     *
     * @param host IPv4 or bracketed IPv6 literal
     * @param port gRPC port to probe
     * @param timeoutMs deadline of this probe
     */
    @NonNull
    ProbeResult probe(@NonNull String host, int port, long timeoutMs);
}
