package com.obby.android.localscreenshare.core.codec.jpeg;

/**
 * Lightweight JPEG frame completeness check: a complete frame is at least four
 * bytes, begins with the SOI marker ({@code FF D8}) and ends with the EOI marker
 * ({@code FF D9}). No structural parsing of the JPEG stream is performed.
 */
public final class JpegFrameValidator {

    private JpegFrameValidator() {
    }

    public static boolean isCompleteFrame(byte[] payload) {
        if (payload == null || payload.length < 4) {
            return false;
        }
        return (payload[0] == (byte) 0xFF && payload[1] == (byte) 0xD8)
                && (payload[payload.length - 2] == (byte) 0xFF
                    && payload[payload.length - 1] == (byte) 0xD9);
    }
}
