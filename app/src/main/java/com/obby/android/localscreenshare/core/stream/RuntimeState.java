package com.obby.android.localscreenshare.core.stream;

/**
 * Lifecycle state of a {@link StreamRuntime}.
 *
 * <p>Allowed transitions:</p>
 * <ul>
 *   <li>{@code IDLE -> CONFIGURING} through {@link StreamRuntime#start()};</li>
 *   <li>{@code CONFIGURING -> READY} when the encoder starts, either immediately for codecs
 *       without configuration parts or on the encoder's configuration report for deferred
 *       codecs;</li>
 *   <li>{@code CONFIGURING} or {@code READY -> FAILED} on a synchronous or asynchronous encoder
 *       failure;</li>
 *   <li>{@code FAILED -> CONFIGURING} only while nothing was published, by retrying
 *       {@code start()};</li>
 *   <li>any state {@code -> CLOSED} through {@link StreamRuntime#close()}.</li>
 * </ul>
 *
 * <p>A successful descriptor switch leaves the runtime in {@code READY}; there is no separate
 * switching state. {@code FAILED} and {@code CLOSED} both stop unit forwarding, but only
 * {@code CLOSED} is terminal.</p>
 */
public enum RuntimeState {
    IDLE, CONFIGURING, READY, FAILED, CLOSED
}
