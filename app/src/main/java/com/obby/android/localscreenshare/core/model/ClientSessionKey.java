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

    /**
     * Number of client-id characters kept for log correlation.
     */
    private static final int CLIENT_ID_TAIL_LENGTH = 8;

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

    /**
     * Returns the client-id tail that correlates both ends of one session in a log capture.
     *
     * <p>The tail is the last {@link #CLIENT_ID_TAIL_LENGTH} characters of the sender's random
     * per-run client id. It is deliberately not an identity: the full id stays inside the process,
     * and eight characters are enough to match two log lines without making a capture traceable
     * back to a device.</p>
     *
     * @param clientId client id, or null when no session exists yet
     * @return the tail, the whole id when it is short enough, or null for a null id
     */
    public static String tailOf(String clientId) {
        if (clientId == null) {
            return null;
        }
        return clientId.length() <= CLIENT_ID_TAIL_LENGTH
                ? clientId
                : clientId.substring(clientId.length() - CLIENT_ID_TAIL_LENGTH);
    }

    /**
     * Returns {@link #tailOf(String)} of this key's client id.
     */
    public String getClientIdTail() {
        return tailOf(clientId);
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
