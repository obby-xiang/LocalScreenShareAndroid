package com.obby.android.localscreenshare.core.support;

import com.obby.android.localscreenshare.core.audio.AudioDecoder;
import com.obby.android.localscreenshare.core.audio.AudioDecoderException;
import com.obby.android.localscreenshare.core.audio.AudioPcm;
import com.obby.android.localscreenshare.core.model.AudioDescriptor;

/**
 * Scripted {@link AudioDecoder} for JVM tests.
 *
 * <p>Configuration records the descriptor; decoding synthesises a PCM buffer of exactly one frame
 * for that descriptor, so the session's framing and buffering logic can be exercised without an AAC
 * codec. Failures and "no output yet" are both injectable.</p>
 */
public final class FakeAudioDecoder implements AudioDecoder {

    private AudioDescriptor descriptor;

    private AudioDecoderException configureFailure;

    private AudioDecoderException decodeFailure;

    private boolean returnNull;

    private int configureCount;

    private int decodeCount;

    private int closeCount;

    public void failConfigure(AudioDecoderException failure) {
        this.configureFailure = failure;
    }

    public void failDecode(AudioDecoderException failure) {
        this.decodeFailure = failure;
    }

    /**
     * Makes every decode report "no output yet", as the platform does before its first frame.
     */
    public void returnNoOutput() {
        this.returnNull = true;
    }

    public AudioDescriptor getDescriptor() {
        return descriptor;
    }

    public int getConfigureCount() {
        return configureCount;
    }

    public int getDecodeCount() {
        return decodeCount;
    }

    public int getCloseCount() {
        return closeCount;
    }

    @Override
    public void configure(AudioDescriptor newDescriptor) throws AudioDecoderException {
        if (configureFailure != null) {
            throw configureFailure;
        }
        descriptor = newDescriptor;
        configureCount++;
    }

    @Override
    public AudioPcm decode(byte[] payload, long ptsUs) throws AudioDecoderException {
        if (decodeFailure != null) {
            throw decodeFailure;
        }
        decodeCount++;
        if (returnNull || descriptor == null) {
            return null;
        }
        final int bytes = descriptor.getFrameSamples() * descriptor.getChannels() * 2;
        return new AudioPcm(new byte[bytes], descriptor.getSampleRate(), descriptor.getChannels());
    }

    @Override
    public void close() {
        closeCount++;
    }
}
