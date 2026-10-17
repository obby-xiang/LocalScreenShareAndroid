package com.obby.android.localscreenshare.core.model;

/**
 * Immutable, self-contained codec configuration fragment (e.g. a CSD-0/SPS entry).
 */
public final class CodecConfigPart {

    private final String id;
    private final byte[] payload;

    public CodecConfigPart(String id, byte[] payload) {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        if (payload == null || payload.length == 0) {
            throw new IllegalArgumentException("payload must not be null or empty");
        }
        this.id = id;
        this.payload = payload.clone();
    }

    public String getId() {
        return id;
    }

    public byte[] getPayload() {
        return payload.clone();
    }
}
