package com.obby.android.localscreenshare.core.net;

/**
 * Lifecycle of one media channel, as its owner sees it.
 *
 * <p>Separate from {@link MediaChannelListener}, which carries media: these three events describe
 * the channel itself. {@code onChannelReady} is reported once the channel is bound and will carry
 * frames, {@code onChannelFailed} at most once when it ended without being asked to, and
 * {@code onJoinPointNeeded} when the channel lost enough data that the decoder needs a fresh
 * random-access point.</p>
 */
public interface MediaChannelStatusListener {

    /**
     * The channel is bound and will deliver frames from now on.
     */
    void onChannelReady();

    /**
     * The channel failed before it ended normally; reported at most once.
     *
     * @param reason short English description for logs
     */
    void onChannelFailed(String reason);

    /**
     * The channel lost a whole access unit and the decoder needs a join point.
     *
     * <p>Only a channel that can lose data reports this; the transport that owns the request
     * throttles it, so a burst of losses does not turn into a burst of key frames.</p>
     */
    default void onJoinPointNeeded() {
    }
}
