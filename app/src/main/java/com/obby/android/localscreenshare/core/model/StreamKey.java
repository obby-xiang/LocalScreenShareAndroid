package com.obby.android.localscreenshare.core.model;

import java.util.Objects;

/**
 * Immutable composite identity of a single media stream within a screen-share session.
 *
 * <p>{@code sessionInstanceId} distinguishes separate runs of the screen-share service, and
 * {@code streamId} distinguishes streams inside one session, so multiple streams remain
 * addressable. Both components must be non-blank. The string form is
 * {@code sessionInstanceId/streamId}; equality and hashing use both components. Instances are map
 * keys in the runtime and the hub and are safe to share between threads.</p>
 */
public final class StreamKey {

    private final String sessionInstanceId;
    private final String streamId;

    public StreamKey(String sessionInstanceId, String streamId) {
        if (sessionInstanceId == null || sessionInstanceId.trim().isEmpty()) {
            throw new IllegalArgumentException("sessionInstanceId must not be null or blank");
        }
        if (streamId == null || streamId.trim().isEmpty()) {
            throw new IllegalArgumentException("streamId must not be null or blank");
        }
        this.sessionInstanceId = sessionInstanceId;
        this.streamId = streamId;
    }

    public String getSessionInstanceId() {
        return sessionInstanceId;
    }

    public String getStreamId() {
        return streamId;
    }

    /** Returns the canonical {@code sessionInstanceId/streamId} form. */
    public String getValue() {
        return sessionInstanceId + "/" + streamId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof StreamKey)) {
            return false;
        }
        StreamKey other = (StreamKey) o;
        return sessionInstanceId.equals(other.sessionInstanceId)
                && streamId.equals(other.streamId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sessionInstanceId, streamId);
    }

    @Override
    public String toString() {
        return getValue();
    }
}
