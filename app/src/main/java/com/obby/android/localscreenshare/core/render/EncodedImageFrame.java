package com.obby.android.localscreenshare.core.render;

/**
 * Immutable compressed image frame handed to a {@link RenderTarget}.
 *
 * <p>{@code payload} holds the compressed image bytes (a complete JPEG in this batch), not
 * decoded pixels; the display side is responsible for decoding. The payload is copied on the way
 * in and out, so the frame is immutable. The width and height are the display dimensions the
 * decoder chose for the frame (typically the descriptor's visible size), not necessarily the
 * coded size embedded in the compressed bitstream.</p>
 */
public final class EncodedImageFrame {

    private final byte[] payload;
    private final long ptsUs;
    private final int width;
    private final int height;

    /**
     * Creates a frame.
     *
     * @param payload compressed image bytes; must be non-null and is copied
     * @param ptsUs   presentation timestamp in microseconds
     * @param width   display width in pixels
     * @param height  display height in pixels
     * @throws IllegalArgumentException if {@code payload} is null
     */
    public EncodedImageFrame(byte[] payload, long ptsUs, int width, int height) {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        this.payload = payload.clone();
        this.ptsUs = ptsUs;
        this.width = width;
        this.height = height;
    }

    public byte[] getPayload() {
        return payload.clone();
    }

    public int getPayloadLength() {
        return payload.length;
    }

    public long getPtsUs() {
        return ptsUs;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }
}
