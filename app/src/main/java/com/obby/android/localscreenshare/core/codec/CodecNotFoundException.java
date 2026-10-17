package com.obby.android.localscreenshare.core.codec;

/**
 * Raised when a codec id is not present in a {@link CodecRegistry}.
 */
public final class CodecNotFoundException extends RuntimeException {

    private final String codecId;

    public CodecNotFoundException(String codecId) {
        super("unknown codec: " + codecId);
        this.codecId = codecId;
    }

    public String getCodecId() {
        return codecId;
    }
}
