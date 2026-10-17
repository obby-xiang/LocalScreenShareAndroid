package com.obby.android.localscreenshare.core.stream;

/**
 * Outcome of a client join attempt.
 */
public final class JoinDecision {

    public enum Status {
        ACCEPTED,
        REQUIRES_EXPLICIT_FALLBACK,
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

    public Status getStatus() {
        return status;
    }

    public ClientSendSession getSession() {
        return session;
    }

    public String getFallbackCodecId() {
        return fallbackCodecId;
    }

    public String getReason() {
        return reason;
    }

    public static JoinDecision accepted(ClientSendSession session) {
        return new JoinDecision(Status.ACCEPTED, session, null, null);
    }

    public static JoinDecision requiresFallback(String fallbackCodecId, String reason) {
        return new JoinDecision(Status.REQUIRES_EXPLICIT_FALLBACK, null, fallbackCodecId, reason);
    }

    public static JoinDecision rejected(String reason) {
        return new JoinDecision(Status.REJECTED, null, null, reason);
    }
}
