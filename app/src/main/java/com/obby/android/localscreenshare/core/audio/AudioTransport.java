package com.obby.android.localscreenshare.core.audio;

import com.obby.android.localscreenshare.core.model.AudioAccessUnit;
import com.obby.android.localscreenshare.core.model.AudioDescriptor;

import java.io.IOException;

/**
 * Transport used to push audio descriptors and frames to one client.
 *
 * <p>Audio travels on the same stream call as video, so the production implementation is the same
 * object that implements {@link com.obby.android.localscreenshare.core.stream.MediaTransport}; the
 * narrow interface exists so the audio send path can be tested without a video transport and so a
 * future second connection would only replace this side.</p>
 *
 * <p><b>Dropping instead of blocking.</b> A send returns {@code false} when the client is not ready
 * to take another message. Audio frames are self-contained and worth about 21 ms, so the caller
 * drops that frame and counts it; audio must never wait behind a slow client, and it must never
 * grow a queue that turns into a burst later.</p>
 *
 * <p><b>Ownership.</b> The transport belongs to the video send session of the same call. An audio
 * session therefore never closes it: closing it would also end video, which is exactly the
 * coupling this design avoids.</p>
 */
public interface AudioTransport {

    /**
     * Sends an audio descriptor.
     *
     * @return true when the message was handed to the transport, false when the client was not
     *         ready and the descriptor must stay pending
     * @throws IOException if the connection failed; the audio session detaches
     */
    boolean sendAudioDescriptor(AudioDescriptor descriptor) throws IOException;

    /**
     * Sends one audio access unit.
     *
     * @return true when the message was handed to the transport, false when the client was not
     *         ready and the frame was dropped
     * @throws IOException if the connection failed; the audio session detaches
     */
    boolean sendAudioUnit(AudioAccessUnit accessUnit) throws IOException;
}
