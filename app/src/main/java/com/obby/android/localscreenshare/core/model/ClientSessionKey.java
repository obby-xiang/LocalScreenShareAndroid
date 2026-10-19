package com.obby.android.localscreenshare.core.model;

import java.util.Objects;

/**
 * Immutable identity of a single client's view of a stream.
 *
 * <p>Carries both the stream identity and the client identity so that multiple streams per client
 * remain addressable. Two keys are equal only when both the stream key and the client id match;
 * the string form is {@code sessionInstanceId/streamId/clientId}. The client id must not be blank.
 * Instances are used as map keys by the stream hub, so they are immutable and have value-based
 * equality.</p>
 */
public final class ClientSessionKey {

    private final StreamKey streamKey;
    private final String clientId;

    /**
     * Creates a session key.
     *
     * @throws IllegalArgumentException if the stream key is null or the client id is null or blank
     */
    public ClientSessionKey(StreamKey streamKey, String clientId) {
        if (streamKey == null) {
            throw new IllegalArgumentException("streamKey must not be null");
        }
        if (clientId == null || clientId.trim().isEmpty()) {
            throw new IllegalArgumentException("clientId must not be null or blank");
        }
        this.streamKey = streamKey;
        this.clientId = clientId;
    }

    public StreamKey getStreamKey() {
        return streamKey;
    }

    public String getClientId() {
        return clientId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ClientSessionKey)) {
            return false;
        }
        ClientSessionKey other = (ClientSessionKey) o;
        return streamKey.equals(other.streamKey) && clientId.equals(other.clientId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(streamKey, clientId);
    }

    @Override
    public String toString() {
        return streamKey.getValue() + "/" + clientId;
    }
}
