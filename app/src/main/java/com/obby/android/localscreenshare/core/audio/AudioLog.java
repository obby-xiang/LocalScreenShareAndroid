package com.obby.android.localscreenshare.core.audio;

/**
 * Diagnostic sink of the client audio path.
 *
 * <p>Audio runs on its own threads and keeps no {@code android.util.Log} dependency: the core audio
 * package is verified on the JVM, where the platform logger is not available. The session therefore
 * reports what it decided - playback started, the output starved, the guard moved the timeline - and
 * the app layer decides where those lines end up. {@link #NONE} is the default, so a session created
 * without a log behaves exactly as it did before diagnostics existed.</p>
 *
 * <p>Messages are complete English sentences with their numbers already formatted, because the
 * interesting quantities (pts, queue depth, measured gap) only exist at the decision point.</p>
 */
public interface AudioLog {

    /**
     * Discards every message; the default of a session without an installed log.
     */
    AudioLog NONE = new AudioLog() {
        @Override
        public void info(String message) {
        }

        @Override
        public void warn(String message) {
        }
    };

    /**
     * Records a decision that explains the normal playback path.
     *
     * @param message English message, never null
     */
    void info(String message);

    /**
     * Records a decision that explains audible trouble.
     *
     * @param message English message, never null
     */
    void warn(String message);
}
