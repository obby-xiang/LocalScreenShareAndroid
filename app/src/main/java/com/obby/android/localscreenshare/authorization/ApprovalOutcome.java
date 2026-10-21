package com.obby.android.localscreenshare.authorization;

/**
 * The user's answer to one approval request.
 *
 * <p>Two of the four answers allow the session: {@link #ALLOW_ONCE} for this call only, and
 * {@link #ALLOW_ALWAYS} which also writes the identity into the durable authorization set. The
 * other two end it: an explicit refusal and the elapsed approval window.</p>
 */
public enum ApprovalOutcome {

    /**
     * The peer may watch, for this call only.
     */
    ALLOW_ONCE,

    /**
     * The peer may watch from now on; the identity is remembered.
     */
    ALLOW_ALWAYS,

    /**
     * The user refused this request.
     */
    DENY,

    /**
     * Nobody answered within the approval window.
     */
    TIMEOUT
}
