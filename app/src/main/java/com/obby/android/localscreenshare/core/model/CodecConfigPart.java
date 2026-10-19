package com.obby.android.localscreenshare.core.model;

/**
 * Immutable, self-contained codec configuration fragment (e.g. a CSD-0/SPS entry).
 *
 * <p>The id is codec-defined (for example {@code sps} or {@code pps}) and only the owning codec
 * interprets the payload. A part must have a non-null id and a non-empty payload. Configuration
 * parts travel in stream descriptors and join information, never inside display access units, and
 * every part contributes to the descriptor's config hash. The byte array is copied defensively on
 * the way in and out, so instances are immutable and safe to share between threads.</p>
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
