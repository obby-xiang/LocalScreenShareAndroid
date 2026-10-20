package com.obby.android.localscreenshare.discovery.register;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Register handshake timing and rate-limit budgets shared by the client, the announcement filter
 * and the server-side call handler.
 *
 * <p>The intervals decide when a peer is registered again; the per-minute budgets bound how many
 * calls the server accepts from one address and globally, and how many reverse calls a client
 * sends on its own.</p>
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RegisterConstants {
    /**
     * Windows in which one address triggers at most one Register call.
     */
    public static final long REGISTER_MERGE_WINDOW_MS = 300L;

    /**
     * Window in which an already confirmed address is not confirmed again.
     *
     * <p>Heartbeats repeat every 5 or 15 seconds, which would exceed the server's
     * {@link #REGISTER_REQUESTS_PER_ADDRESS_PER_MINUTE per-address quota} and turn a healthy peer
     * into a rate-limited one. A confirmation this recent is still inside the store's confirmed
     * TTL, so skipping the call costs no accuracy.</p>
     */
    public static final long REGISTER_RECONFIRM_INTERVAL_MS = 30_000L;

    /**
     * Deadline of one Register call, in milliseconds.
     */
    public static final long REGISTER_TIMEOUT_MS = 500L;

    /**
     * Retry delays of a Register call whose attempt failed or timed out.
     */
    public static final long[] REGISTER_RETRY_DELAYS_MS = {200L, 500L};

    /**
     * Register calls accepted per remote address within one minute.
     */
    public static final int REGISTER_REQUESTS_PER_ADDRESS_PER_MINUTE = 10;

    /**
     * Register calls accepted globally within one minute.
     */
    public static final int REGISTER_REQUESTS_PER_MINUTE = 100;

    /**
     * Unsolicited reverse Register calls sent within one minute.
     */
    public static final int REGISTER_REVERSE_PER_MINUTE = 30;

    /**
     * Upper bound of a reverse Register delay, in milliseconds; the delay is random.
     */
    public static final long REGISTER_REVERSE_MAX_DELAY_MS = 200L;
}
