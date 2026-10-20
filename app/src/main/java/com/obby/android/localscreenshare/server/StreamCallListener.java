package com.obby.android.localscreenshare.server;

import com.obby.android.localscreenshare.core.model.ClientSessionKey;

/**
 * Receives the stream-call events that are worth one structured log line.
 *
 * <p>{@link StreamCallHandler} reports here instead of writing to logcat itself, so the handler
 * stays testable on the JVM. {@link ScreenShareServer} implements it with {@code android.util.Log};
 * tests use a recording implementation. Implementations must be thread-safe: events are delivered
 * from the gRPC callback thread that handles HELLO and from the per-client drain thread.</p>
 */
public interface StreamCallListener {

    /**
     * Listener that ignores every event, used when the caller does not observe stream calls.
     */
    StreamCallListener NO_OP = new StreamCallListener() {
        @Override
        public void onSessionTakenOver(ClientSessionKey key, String transportId) {
        }

        @Override
        public void onJoinRejected(ClientSessionKey key, String transportId, String reason) {
        }
    };

    /**
     * Reports that a newer connection replaced the session of the call that lost it.
     *
     * @param key         session key shared by the replaced and the replacing connection
     * @param transportId identity of the transport of the replaced call
     */
    void onSessionTakenOver(ClientSessionKey key, String transportId);

    /**
     * Reports that the join was refused and the call is being closed.
     *
     * @param key         session key the call tried to attach with
     * @param transportId identity of the call's transport
     * @param reason      the refusal reason reported by the stream hub
     */
    void onJoinRejected(ClientSessionKey key, String transportId, String reason);
}
