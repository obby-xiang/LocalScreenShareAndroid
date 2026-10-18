package com.obby.android.localscreenshare.core.codec.h264;

/**
 * Signals malformed or unsupported H.264 bitstream input. Unchecked so that
 * parsing entry points can be called from policy code without forcing every
 * caller to handle the failure; {@code isIdrAccessUnit} catches it internally.
 */
public class H264FormatException extends RuntimeException {

    public H264FormatException(String message) {
        super(message);
    }

    public H264FormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
