package com.obby.android.localscreenshare.core.codec;

/**
 * Signals that a decoder needs a fresh random-access point from the server.
 *
 * <p>A decoder drops frames it cannot accept — an input buffer that is not available within the
 * budget — and a dropped P frame breaks the reference chain until the next IDR. Reporting the event
 * lets the client ask for a join point immediately instead of waiting for the periodic key-frame
 * interval. The callback runs on the decoder's thread and must not block; rate limiting belongs to
 * the implementation that sends the request, because several decoders and several failure paths
 * share one request.</p>
 */
@FunctionalInterface
public interface KeyFrameRequester {

    /**
     * No-op requester used when the caller does not care about recovery.
     */
    KeyFrameRequester NONE = () -> {
    };

    /**
     * Reports that the decoder needs a new random-access point.
     */
    void requestKeyFrame();
}
