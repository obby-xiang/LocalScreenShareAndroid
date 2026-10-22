package com.obby.android.localscreenshare.server;

import com.obby.android.localscreenshare.core.stream.MediaTransport;
import com.obby.android.localscreenshare.core.stream.MediaTransportRecovery;

import java.io.IOException;

/**
 * Returns the media of a call to the control-plane carrier after a media channel failed.
 *
 * <p>The carrier of a call is the transport that also carries its control stream, so it is the one
 * transport that must never be closed by a fallback: the session swaps back to it, re-sends the
 * current descriptor and waits for a join point, and the call never leaves the RPC it started on.
 * A failure of the carrier itself is therefore unrecoverable and reported as such.</p>
 *
 * <p>A fallback asks the encoder for a join point, because the units buffered for the failed
 * channel were dropped: without a fresh random-access point the viewer would receive reference
 * frames it cannot decode. The request runs on the thread whose send failed, which is the drain
 * thread of this call, and never on a probe.</p>
 */
public final class StreamMediaRecovery implements MediaTransportRecovery {

    private final MediaTransport carrier;

    private final Runnable joinPointRequester;

    /**
     * Creates the recovery of one call.
     *
     * @param carrier           transport that also carries the control stream; required
     * @param joinPointRequester asks the runtime for a random-access point after a fallback;
     *                           required
     * @throws IllegalArgumentException if an argument is null
     */
    public StreamMediaRecovery(MediaTransport carrier, Runnable joinPointRequester) {
        if (carrier == null) {
            throw new IllegalArgumentException("carrier must not be null");
        }
        if (joinPointRequester == null) {
            throw new IllegalArgumentException("joinPointRequester must not be null");
        }
        this.carrier = carrier;
        this.joinPointRequester = joinPointRequester;
    }

    @Override
    public MediaTransport onTransportFailed(MediaTransport failed, IOException cause) {
        if (failed == carrier) {
            return null;
        }
        joinPointRequester.run();
        return carrier;
    }
}
