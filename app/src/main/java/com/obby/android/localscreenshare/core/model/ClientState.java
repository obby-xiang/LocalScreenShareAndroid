package com.obby.android.localscreenshare.core.model;

/**
 * Lifecycle state of a single client session.
 *
 * <p>A session starts in {@link #NEGOTIATING}. The state machine is driven by descriptors and
 * access units from the server:</p>
 * <ul>
 *   <li>{@code NEGOTIATING} to {@code WAIT_CONFIG} when a supported descriptor arrives, to
 *       {@code RESYNCING} when a required surface is missing, and to {@code CLOSED} for an
 *       unsupported codec or an explicit close.</li>
 *   <li>{@code WAIT_CONFIG} to {@code WAIT_RANDOM_ACCESS} when the decoder is configured, or to
 *       {@code RESYNCING} when configuration fails.</li>
 *   <li>{@code WAIT_RANDOM_ACCESS} to {@code STREAMING} on a validated join point; access units
 *       before that point are dropped.</li>
 *   <li>{@code STREAMING} to {@code RESYNCING} on a decode failure or a unit from a future
 *       epoch.</li>
 *   <li>{@code RESYNCING} to {@code STREAMING} on a validated join point of the current epoch, to
 *       {@code WAIT_RANDOM_ACCESS} after a successful retry, or to {@code WAIT_CONFIG} when a new
 *       descriptor is applied.</li>
 *   <li>Any state to {@code CLOSED} through an explicit close; {@code CLOSED} is terminal.</li>
 * </ul>
 * <p>A descriptor with a newer epoch can move {@code WAIT_RANDOM_ACCESS} or {@code STREAMING}
 * back to {@code WAIT_CONFIG}. Listeners are notified only on actual transitions, never when the
 * state is set to its current value.</p>
 */
public enum ClientState {
    /** No usable descriptor yet; no codec has been selected. */
    NEGOTIATING,

    /** A descriptor was accepted and the decoder is being created and configured. */
    WAIT_CONFIG,

    /** The decoder is configured and waiting for a random-access point. */
    WAIT_RANDOM_ACCESS,

    /** Decoding same-epoch access units continuously. */
    STREAMING,

    /** The decode pipeline was lost; units are dropped until a join point restores it. */
    RESYNCING,

    /** Terminal state; no further transitions and no further output. */
    CLOSED
}
