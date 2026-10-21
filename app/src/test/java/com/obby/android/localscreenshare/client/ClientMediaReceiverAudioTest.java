package com.obby.android.localscreenshare.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.obby.android.localscreenshare.core.audio.AudioCodecIds;
import com.obby.android.localscreenshare.core.audio.AudioDecoder;
import com.obby.android.localscreenshare.core.audio.AudioDecoderException;
import com.obby.android.localscreenshare.core.audio.AudioPcm;
import com.obby.android.localscreenshare.core.audio.ClientAudioSession;
import com.obby.android.localscreenshare.core.client.ClientMediaSession;
import com.obby.android.localscreenshare.core.codec.CodecRegistry;
import com.obby.android.localscreenshare.core.model.AudioDescriptor;
import com.obby.android.localscreenshare.core.model.ClientSessionKey;
import com.obby.android.localscreenshare.core.model.ClientState;
import com.obby.android.localscreenshare.core.model.CodecConfigPart;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.stream.ClientCapabilities;
import com.obby.android.localscreenshare.core.support.FakeCodec;
import com.obby.android.localscreenshare.core.support.FakeRenderTarget;
import com.obby.android.localscreenshare.core.support.MutableAudioClock;
import com.obby.android.localscreenshare.grpc.screenstream.ServerMessage;
import com.obby.android.localscreenshare.transport.ScreenStreamProtoMapper;

import org.junit.Before;
import org.junit.Test;

import java.util.Collections;

/**
 * Unit tests for the audio branches of {@link ClientMediaReceiver}.
 *
 * <p>They assert the isolation the audio design promises: audio messages reach only the audio
 * session, a malformed audio message is swallowed, and the video session's state never moves
 * because of audio.</p>
 */
public class ClientMediaReceiverAudioTest {

    private static final StreamKey STREAM_KEY = new StreamKey("session-1", "stream-1");

    private static final ClientSessionKey CLIENT_KEY = new ClientSessionKey(STREAM_KEY, "client-1");

    private FakeCodec codec;

    private ClientMediaSession session;

    private ClientAudioSession audioSession;

    private MutableAudioClock clock;

    private ClientMediaReceiver receiver;

    @Before
    public void setUp() {
        codec = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        final CodecRegistry registry = new CodecRegistry();
        registry.register(codec.registration());
        session = new ClientMediaSession(CLIENT_KEY, registry,
                new ClientCapabilities(Collections.singleton(FakeCodec.DEFAULT_CODEC_ID)),
                new FakeRenderTarget(), null);
        clock = new MutableAudioClock(0L);
        audioSession = new ClientAudioSession(new FixedDecoder(), new NoopSink(), null, clock,
                false);
        receiver = new ClientMediaReceiver(STREAM_KEY, session, null, null, null,
                com.obby.android.localscreenshare.core.metrics.PipelineMetrics.NONE, audioSession);
    }

    @Test
    public void audioDescriptorReachesOnlyTheAudioSession() {
        receiver.onServerMessage(ServerMessage.newBuilder()
                .setAudioDescriptor(ScreenStreamProtoMapper.toProtoAudioDescriptor(descriptor()))
                .build());

        assertEquals(ClientAudioSession.State.ACTIVE, audioSession.getState());
        assertEquals(ClientState.NEGOTIATING, session.getState());
        assertNull(session.getCurrentDescriptor());
    }

    @Test
    public void malformedAudioDescriptorDoesNotDisturbTheVideoSession() {
        receiver.onServerMessage(ServerMessage.newBuilder()
                .setAudioDescriptor(ScreenStreamProtoMapper.toProtoAudioDescriptor(descriptor())
                        .toBuilder().setSampleRate(0).build())
                .build());

        assertEquals(ClientAudioSession.State.IDLE, audioSession.getState());
        assertEquals(ClientState.NEGOTIATING, session.getState());
    }

    @Test
    public void audioAccessUnitWithoutDescriptorIsIgnored() {
        receiver.onServerMessage(ServerMessage.newBuilder()
                .setAudioAccessUnit(com.obby.android.localscreenshare.grpc.screenstream
                        .AudioAccessUnit.newBuilder()
                        .setConfigEpoch(1)
                        .setSequence(0)
                        .setPtsUs(0)
                        .setPayload(com.google.protobuf.ByteString.copyFrom(new byte[]{0x21}))
                        .build())
                .build());

        assertEquals(ClientAudioSession.State.IDLE, audioSession.getState());
        assertEquals(ClientState.NEGOTIATING, session.getState());
    }

    @Test
    public void receiverWithoutAnAudioSessionIgnoresAudioMessages() {
        final ClientMediaReceiver videoOnly = new ClientMediaReceiver(STREAM_KEY, session, null,
                null, null, com.obby.android.localscreenshare.core.metrics.PipelineMetrics.NONE);

        videoOnly.onServerMessage(ServerMessage.newBuilder()
                .setAudioDescriptor(ScreenStreamProtoMapper.toProtoAudioDescriptor(descriptor()))
                .build());

        assertEquals(ClientState.NEGOTIATING, session.getState());
    }

    @Test
    public void closeClosesBothSessions() {
        receiver.close();
        receiver.close();

        assertEquals(ClientAudioSession.State.CLOSED, audioSession.getState());
        assertEquals(ClientState.CLOSED, session.getState());
    }

    private static AudioDescriptor descriptor() {
        return AudioDescriptor.builder(1, AudioCodecIds.AAC, AudioCodecIds.AAC_MIME, 48_000, 2,
                        128_000)
                .configParts(Collections.singletonList(
                        new CodecConfigPart(AudioCodecIds.CONFIG_PART_CSD_0,
                                new byte[]{0x12, 0x10})))
                .build();
    }

    /**
     * Decoder that always emits one PCM frame, so the session reaches its player without a codec.
     */
    private static final class FixedDecoder implements AudioDecoder {

        private AudioDescriptor descriptor;

        @Override
        public void configure(AudioDescriptor newDescriptor) throws AudioDecoderException {
            descriptor = newDescriptor;
        }

        @Override
        public AudioPcm decode(byte[] payload, long ptsUs) {
            final int bytes = descriptor.getFrameSamples() * descriptor.getChannels() * 2;
            return new AudioPcm(new byte[bytes], descriptor.getSampleRate(),
                    descriptor.getChannels());
        }

        @Override
        public void close() {
        }
    }

    /**
     * Sink that accepts everything; the test only cares that audio stays off the video path.
     */
    private static final class NoopSink implements com.obby.android.localscreenshare.core.audio.AudioSink {

        private boolean open;

        @Override
        public void open(int sampleRate, int channels) {
            open = true;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void write(byte[] pcm, int offset, int count) {
        }

        @Override
        public void setVolume(float volume) {
        }

        @Override
        public void play() {
        }

        @Override
        public void pause() {
        }

        @Override
        public void release() {
            open = false;
        }

        @Override
        public long getPresentedFrames(long nowNs) {
            return -1L;
        }
    }
}
