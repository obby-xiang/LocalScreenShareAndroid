package com.obby.android.localscreenshare.core.codec;

/**
 * Encoder that accepts already-compressed frames instead of consuming a surface.
 *
 * <p>Surface-input encoders receive frames through {@link Encoder#getInputSurface()}; frame-input
 * encoders such as JPEG receive complete encoded frames through {@link #submitFrame}. A runtime
 * submits to whichever active encoder implements this interface and ignores the call for other
 * codecs.</p>
 */
public interface FrameInputEncoder {

    /**
     * Submits one complete encoded frame.
     *
     * @param ptsUs         presentation timestamp in microseconds; must be non-negative
     * @param encodedBytes  the complete encoded frame payload; must not be null or empty
     * @return {@code true} when the frame was accepted and emitted, {@code false} when it was
     *         rejected because the encoder is not running or the payload is not a complete frame
     */
    boolean submitFrame(long ptsUs, byte[] encodedBytes);
}
