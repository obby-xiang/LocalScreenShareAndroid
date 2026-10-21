package com.obby.android.localscreenshare.core.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.obby.android.localscreenshare.core.model.AudioAccessUnit;
import com.obby.android.localscreenshare.core.model.AudioCodecCapability;
import com.obby.android.localscreenshare.core.model.AudioDescriptor;
import com.obby.android.localscreenshare.core.model.ClientSessionKey;
import com.obby.android.localscreenshare.core.model.CodecConfigPart;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.stream.ClientCapabilities;
import com.obby.android.localscreenshare.core.stream.CodecCapability;
import com.obby.android.localscreenshare.core.support.FakeAudioCaptureSource;
import com.obby.android.localscreenshare.core.support.FakeAudioDecoder;
import com.obby.android.localscreenshare.core.support.FakeAudioEncoderBridge;
import com.obby.android.localscreenshare.core.support.FakeAudioSink;
import com.obby.android.localscreenshare.core.support.FakeAudioTransport;
import com.obby.android.localscreenshare.core.support.MutableAudioClock;

import org.junit.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Degradation matrix of the audio track: every failure closes audio only.
 *
 * <p>The rows that can be decided on the JVM are exercised here with the same fakes the batch tests
 * use: capture open failures, read failures while running, encoder failures, a silent source that
 * is not a fault, a stopped pipeline that stays quiet, a client without a decoder and a transport
 * failure that removes one session but not the other. Device-only rows (OEM capture policy, real
 * encoder latency) are listed in the plan's device checklist instead.</p>
 */
public class AudioDegradationMatrixTest {

    private static final StreamKey STREAM_KEY = new StreamKey("session", "stream");

    private static final ClientSessionKey CLIENT_A =
            new ClientSessionKey(STREAM_KEY, "client-a");

    private static final ClientSessionKey CLIENT_B =
            new ClientSessionKey(STREAM_KEY, "client-b");

    @Test
    public void everyCaptureOpenFailureKeepsItsReason() {
        for (AudioCaptureException.Reason reason : new AudioCaptureException.Reason[]{
                AudioCaptureException.Reason.PERMISSION_DENIED,
                AudioCaptureException.Reason.UNSUPPORTED,
                AudioCaptureException.Reason.DEVICE_BUSY,
                AudioCaptureException.Reason.ENCODER_FAILED}) {
            final AudioCaptureException failure = new AudioCaptureException(reason, "row");
            FakeAudioCaptureSource source = new FakeAudioCaptureSource(480, 4).failOpen(failure);
            AudioPipeline pipeline = new AudioPipeline(source,
                    new AacEncoder(new FakeAudioEncoderBridge()), new NoopListener());

            AudioCaptureException thrown = assertThrows(AudioCaptureException.class,
                    () -> pipeline.start(1, 48_000, 1, 128_000));

            assertEquals(reason, thrown.getReason());
            assertEquals(AudioAvailability.fromFailure(reason),
                    AudioAvailability.fromFailure(thrown.getReason()));
            assertFalse(pipeline.isRunning());
        }
    }

    @Test
    public void aReadFailureMidStreamStopsTheWorkerAndReportsTheReason() throws Exception {
        RecordingListener listener = new RecordingListener();
        FakeAudioCaptureSource source = new FakeAudioCaptureSource(480, 100).failReadsAfter(1,
                new AudioCaptureException(AudioCaptureException.Reason.DEVICE_BUSY, "busy"));
        AudioPipeline pipeline = new AudioPipeline(source,
                new AacEncoder(new FakeAudioEncoderBridge()), listener);

        pipeline.start(1, 48_000, 1, 128_000);

        assertTrue(listener.awaitFailure(2_000L));
        assertEquals(AudioCaptureException.Reason.DEVICE_BUSY, listener.failure.getReason());
        assertFalse(pipeline.isRunning());
        pipeline.stop();
    }

    @Test
    public void anEncoderFailureMidStreamIsReportedAsEncoderFailed() throws Exception {
        RecordingListener listener = new RecordingListener();
        FakeAudioEncoderBridge bridge = new FakeAudioEncoderBridge().failQueue(
                new AudioCaptureException(AudioCaptureException.Reason.ENCODER_FAILED, "boom"));
        AudioPipeline pipeline = new AudioPipeline(new FakeAudioCaptureSource(2048, 4),
                new AacEncoder(bridge), listener);
        pipeline.start(1, 48_000, 1, 128_000);

        assertTrue(listener.awaitFailure(2_000L));
        assertEquals(AudioCaptureException.Reason.ENCODER_FAILED, listener.failure.getReason());
        assertFalse(pipeline.isRunning());
        pipeline.stop();
    }

    @Test
    public void aSilentSourceIsNotAFailure() throws Exception {
        RecordingListener listener = new RecordingListener();
        // Every read returns zero samples, exactly what a muted device or another capturer yields.
        AudioPipeline pipeline = new AudioPipeline(new FakeAudioCaptureSource(0, 0),
                new AacEncoder(new FakeAudioEncoderBridge()), listener);

        pipeline.start(1, 48_000, 1, 128_000);
        Thread.sleep(50L);

        assertTrue(pipeline.isRunning());
        assertEquals(0, listener.units.size());
        assertEquals(0, listener.failures.size());
        pipeline.stop();
    }

    @Test
    public void aStoppedPipelineReportsNothingAgain() throws Exception {
        RecordingListener listener = new RecordingListener();
        AudioPipeline pipeline = new AudioPipeline(new FakeAudioCaptureSource(480, 100),
                new AacEncoder(new FakeAudioEncoderBridge()), listener);
        pipeline.start(1, 48_000, 1, 128_000);
        pipeline.stop();
        final int units = listener.units.size();
        final int failures = listener.failures.size();

        Thread.sleep(50L);

        assertEquals(units, listener.units.size());
        assertEquals(failures, listener.failures.size());
        assertFalse(pipeline.isRunning());
    }

    @Test
    public void aClientWithoutAnAudioDecoderNeverGetsASession() throws Exception {
        AudioHub hub = new AudioHub();
        hub.onDescriptor(descriptor(1));

        assertFalse(hub.join(CLIENT_A, new ClientCapabilities(Collections.singleton("h264")),
                new FakeAudioTransport()));
        hub.onAccessUnit(unit(1, 0, 0L));

        assertEquals(0, hub.getSessionCount());
        assertFalse(hub.drainOne(CLIENT_A));
    }

    @Test
    public void aTransportFailureRemovesOneAudioSessionOnly() throws Exception {
        AudioHub hub = new AudioHub();
        hub.onDescriptor(descriptor(1));
        FakeAudioTransport failing = new FakeAudioTransport();
        FakeAudioTransport healthy = new FakeAudioTransport();
        hub.join(CLIENT_A, audioCaps(), failing);
        hub.join(CLIENT_B, audioCaps(), healthy);
        failing.failNextSend();

        assertThrows(IOException.class, () -> hub.drainOne(CLIENT_A));

        assertEquals(1, hub.getSessionCount());
        hub.onAccessUnit(unit(1, 0, 0L));
        while (hub.drainOne(CLIENT_B)) {
            // drain the healthy client only
        }
        assertEquals(1, healthy.getAccessUnitCount());
    }

    @Test
    public void clientAudioFailuresStayInsideTheAudioSession() {
        FakeAudioDecoder decoder = new FakeAudioDecoder();
        decoder.failConfigure(new AudioDecoderException("no decoder"));
        ClientAudioSession session = new ClientAudioSession(decoder, new FakeAudioSink(), null,
                new MutableAudioClock(0L), false);

        session.onDescriptor(descriptor(1));

        assertEquals(ClientAudioSession.State.UNAVAILABLE, session.getState());
        // The video session is a different object and is not referenced here at all; the receiver
        // test additionally asserts that video messages keep decoding after an audio failure.
        assertNotNull(session.getUnavailableReason());
        session.close();
        assertEquals(ClientAudioSession.State.CLOSED, session.getState());
    }

    private static ClientCapabilities audioCaps() {
        Map<String, CodecCapability> video =
                Collections.singletonMap("h264", CodecCapability.any("h264"));
        Map<String, AudioCodecCapability> audio =
                Collections.singletonMap("aac", AudioCodecCapability.any("aac"));
        return new ClientCapabilities(video, audio);
    }

    private static AudioDescriptor descriptor(int epoch) {
        return AudioDescriptor.builder(epoch, AudioCodecIds.AAC, AudioCodecIds.AAC_MIME, 48_000,
                        2, 128_000)
                .configParts(Collections.singletonList(
                        new CodecConfigPart(AudioCodecIds.CONFIG_PART_CSD_0,
                                new byte[]{0x12, 0x10})))
                .build();
    }

    private static AudioAccessUnit unit(int epoch, long sequence, long ptsUs) {
        return new AudioAccessUnit(epoch, sequence, ptsUs, new byte[]{0x21, 0x01});
    }

    private static final class NoopListener implements AudioPipeline.Listener {

        @Override
        public void onDescriptor(AudioDescriptor descriptor) {
        }

        @Override
        public void onAccessUnit(AudioAccessUnit accessUnit) {
        }

        @Override
        public void onUnavailable(AudioCaptureException failure) {
        }
    }

    private static final class RecordingListener implements AudioPipeline.Listener {

        private final CountDownLatch failureLatch = new CountDownLatch(1);

        private final java.util.List<AudioAccessUnit> units =
                Collections.synchronizedList(new java.util.ArrayList<AudioAccessUnit>());

        private final java.util.List<AudioCaptureException> failures =
                Collections.synchronizedList(
                        new java.util.ArrayList<AudioCaptureException>());

        private volatile AudioCaptureException failure;

        @Override
        public void onDescriptor(AudioDescriptor descriptor) {
        }

        @Override
        public void onAccessUnit(AudioAccessUnit accessUnit) {
            units.add(accessUnit);
        }

        @Override
        public void onUnavailable(AudioCaptureException cause) {
            failure = cause;
            failures.add(cause);
            failureLatch.countDown();
        }

        boolean awaitFailure(long timeoutMs) throws InterruptedException {
            return failureLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        }
    }
}
