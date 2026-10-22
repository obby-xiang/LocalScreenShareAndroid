package com.obby.android.localscreenshare.core.net;

/**
 * Diagnostic sink of a media channel negotiation.
 *
 * <p>Negotiation decides whether a call keeps its control-plane carrier or moves to a dedicated
 * channel, and a field report had no way to tell why a channel was never used: neither end logged
 * its reason. This interface is the seam that fixes that without pulling the platform logger into
 * classes the JVM tests construct - the app layer installs the implementation and decides where the
 * lines end up, exactly like {@code core/audio/AudioLog} does for the audio path.</p>
 *
 * <p>{@link #NONE} is the default, so a negotiation created without a log behaves exactly as it did
 * before diagnostics existed. Messages are complete English sentences with their values already
 * formatted, because the interesting quantities (transport, port, reason) only exist at the decision
 * point.</p>
 */
public interface MediaChannelLog {

    /**
     * Discards every message; the default of a negotiation without an installed log.
     */
    MediaChannelLog NONE = new MediaChannelLog() {
        @Override
        public void info(String message) {
        }

        @Override
        public void warn(String message) {
        }
    };

    /**
     * Records a step of the negotiation that went as intended.
     *
     * @param message English message, never null
     */
    void info(String message);

    /**
     * Records a channel that was refused, dropped or never answered.
     *
     * @param message English message, never null
     */
    void warn(String message);
}
