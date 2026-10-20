package com.obby.android.localscreenshare.core.stream;

/**
 * Outcome of a client join attempt.
 *
 * <p>A decision is immutable and describes a single attempt. Only an accepted decision carries a
 * session. A not-ready decision is transient: the active runtime has not published a usable
 * descriptor yet, nothing was reserved, and the join may be attempted again later. A fallback
 * decision names the codec the caller would have to switch to; the hub neither switches the active
 * stream nor creates a session for it. A rejected decision means the attempt cannot succeed as
 * requested - no active stream, a mismatched stream key, an existing session for the key, or no
 * codec supported by all clients.</p>
 */
public final class JoinDecision {

    /**
     * Kind of outcome; see {@link JoinDecision}.
     */
    public enum Status {
        /**
         * A session was created and attached.
         */
        ACCEPTED,
        /**
         * The active stream is not ready to serve a new client yet; retry later.
         */
        NOT_READY,
        /**
         * The client needs a different codec; the caller must decide whether to switch.
         */
        REQUIRES_EXPLICIT_FALLBACK,
        /**
         * The attempt failed and must not be retried unchanged.
         */
        REJECTED
    }

    private final Status status;
    private final ClientSendSession session;
    private final String fallbackCodecId;
    private final String reason;

    private JoinDecision(Status status, ClientSendSession session, String fallbackCodecId,
                         String reason) {
        this.status = status;
        this.session = session;
        this.fallbackCodecId = fallbackCodecId;
        this.reason = reason;
    }

    /**
     * Returns the outcome kind.
     */
    public Status getStatus() {
        return status;
    }

    /**
     * Returns the attached session for an accepted decision, otherwise {@code null}.
     */
    public ClientSendSession getSession() {
        return session;
    }

    /**
     * Returns the candidate codec for a fallback decision, otherwise {@code null}.
     */
    public String getFallbackCodecId() {
        return fallbackCodecId;
    }

    /**
     * Returns the explanation for a non-accepted outcome, or {@code null} for an accepted one.
     */
    public String getReason() {
        return reason;
    }

    /**
     * Creates an accepted decision carrying the session that was attached.
     */
    public static JoinDecision accepted(ClientSendSession session) {
        return new JoinDecision(Status.ACCEPTED, session, null, null);
    }

    /**
     * Creates a decision that names the codec the caller would have to switch to.
     *
     * <p>No session is attached and the active stream is untouched; switching is an explicit
     * decision outside the hub.</p>
     */
    public static JoinDecision requiresFallback(String fallbackCodecId, String reason) {
        return new JoinDecision(Status.REQUIRES_EXPLICIT_FALLBACK, null, fallbackCodecId, reason);
    }

    /**
     * Creates a rejected decision; the same request must not be retried unchanged.
     */
    public static JoinDecision rejected(String reason) {
        return new JoinDecision(Status.REJECTED, null, null, reason);
    }

    /**
     * Creates a not-ready decision; the request reserved nothing and may be retried once the
     * active stream has published its descriptor.
     */
    public static JoinDecision notReady(String reason) {
        return new JoinDecision(Status.NOT_READY, null, null, reason);
    }
}
