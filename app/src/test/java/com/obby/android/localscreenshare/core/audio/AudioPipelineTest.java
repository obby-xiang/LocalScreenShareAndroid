package com.obby.android.localscreenshare.core.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.obby.android.localscreenshare.core.model.AudioAccessUnit;
import com.obby.android.localscreenshare.core.model.AudioDescriptor;
import com.obby.android.localscreenshare.core.support.FakeAudioCaptureSource;
import com.obby.android.localscreenshare.core.support.FakeAudioEncoderBridge;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Unit tests for {@link AudioPipeline}.
 */
public class AudioPipelineTest {

    private static final int SAMPLE_RATE = 48_000;

    @Test
    public void publishesDescriptorBeforeFramesAndStopsCleanly() throws Exception {
        FakeAudioCaptureSource source = new FakeAudioCaptureSource(480, 20);
        FakeAudioEncoderBridge bridge = new FakeAudioEncoderBridge();
        RecordingListener listener = new RecordingListener(2);

        AudioPipeline pipeline = new AudioPipeline(source, new AacEncoder(bridge), listener);
        pipeline.start(1, SAMPLE_RATE, 1, 128_000);

        assertTrue(listener.awaitFrames(2_000L));
        pipeline.stop();

        assertEquals(1, source.getOpenCount());
        assertEquals(1, source.getCloseCount());
        assertTrue(bridge.isClosed());
        assertFalse(pipeline.isRunning());
        assertEquals(1, listener.descriptors.size());
        assertEquals(1, listener.firstEventIsDescriptor());
        assertTrue(listener.units.size() >= 2);
        assertTrue(listener.units.get(0).getPtsUs() >= 0L);
        assertEquals(1, listener.units.get(0).getConfigEpoch());
    }

    @Test
    public void idleReadsDoNotProduceFramesOrSpin() throws Exception {
        FakeAudioCaptureSource source = new FakeAudioCaptureSource(0, 0);
        FakeAudioEncoderBridge bridge = new FakeAudioEncoderBridge();
        RecordingListener listener = new RecordingListener(1);

        AudioPipeline pipeline = new AudioPipeline(source, new AacEncoder(bridge), listener);
        pipeline.start(1, SAMPLE_RATE, 2, 128_000);
        assertTrue(source.awaitFirstRead(2_000L));

        assertTrue(pipeline.isRunning());
        assertEquals(0, listener.units.size());
        pipeline.stop();

        assertFalse(pipeline.isRunning());
    }

    @Test
    public void captureFailureReportsReasonAndStopsTheWorker() throws Exception {
        AudioCaptureException failure = new AudioCaptureException(
                AudioCaptureException.Reason.PERMISSION_DENIED, "denied");
        FakeAudioCaptureSource source = new FakeAudioCaptureSource(480, 100)
                .failReadsAfter(0, failure);
        RecordingListener listener = new RecordingListener(1);
        AudioPipeline pipeline = new AudioPipeline(source, new AacEncoder(
                new FakeAudioEncoderBridge()), listener);

        pipeline.start(1, SAMPLE_RATE, 1, 128_000);

        assertTrue(listener.awaitFailure(2_000L));
        assertEquals(AudioCaptureException.Reason.PERMISSION_DENIED,
                listener.failure.getReason());
        assertFalse(pipeline.isRunning());
        pipeline.stop();
        assertEquals(1, source.getCloseCount());
    }

    @Test
    public void openFailurePreventsTheWorkerFromStarting() {
        AudioCaptureException failure = new AudioCaptureException(
                AudioCaptureException.Reason.DEVICE_BUSY, "busy");
        FakeAudioCaptureSource source = new FakeAudioCaptureSource(480, 10).failOpen(failure);
        FakeAudioEncoderBridge bridge = new FakeAudioEncoderBridge();
        AudioPipeline pipeline = new AudioPipeline(source, new AacEncoder(bridge),
                new RecordingListener(1));

        AudioCaptureException thrown = assertThrows(AudioCaptureException.class,
                () -> pipeline.start(1, SAMPLE_RATE, 1, 128_000));

        assertEquals(AudioCaptureException.Reason.DEVICE_BUSY, thrown.getReason());
        assertFalse(pipeline.isRunning());
        assertFalse(bridge.isConfigured());
    }

    @Test
    public void encoderConfigurationFailureClosesTheSource() {
        AudioCaptureException failure = new AudioCaptureException(
                AudioCaptureException.Reason.ENCODER_FAILED, "no encoder");
        FakeAudioCaptureSource source = new FakeAudioCaptureSource(480, 10);
        FakeAudioEncoderBridge bridge = new FakeAudioEncoderBridge().failConfigure(failure);
        AudioPipeline pipeline = new AudioPipeline(source, new AacEncoder(bridge),
                new RecordingListener(1));

        assertThrows(AudioCaptureException.class,
                () -> pipeline.start(1, SAMPLE_RATE, 1, 128_000));

        assertEquals(1, source.getCloseCount());
        assertFalse(pipeline.isRunning());
    }

    @Test
    public void stopIsIdempotentAndStartAfterStopWorks() throws Exception {
        FakeAudioCaptureSource source = new FakeAudioCaptureSource(480, 20);
        AudioPipeline pipeline = new AudioPipeline(source,
                new AacEncoder(new FakeAudioEncoderBridge()), new RecordingListener(1));

        pipeline.start(1, SAMPLE_RATE, 1, 128_000);
        pipeline.stop();
        pipeline.stop();

        assertEquals(2, source.getCloseCount());
        pipeline.start(2, SAMPLE_RATE, 1, 128_000);
        assertTrue(pipeline.isRunning());
        pipeline.stop();
    }

    /**
     * Records pipeline events in order and latches the first frame and the first failure.
     */
    private static final class RecordingListener implements AudioPipeline.Listener {

        private final List<AudioDescriptor> descriptors =
                Collections.synchronizedList(new ArrayList<>());

        private final List<AudioAccessUnit> units =
                Collections.synchronizedList(new ArrayList<>());

        private final List<String> events = Collections.synchronizedList(new ArrayList<>());

        private final CountDownLatch frameLatch;

        private final CountDownLatch failureLatch = new CountDownLatch(1);

        private volatile AudioCaptureException failure;

        RecordingListener(int expectedFrames) {
            this.frameLatch = new CountDownLatch(expectedFrames);
        }

        @Override
        public void onDescriptor(AudioDescriptor descriptor) {
            descriptors.add(descriptor);
            events.add("descriptor");
        }

        @Override
        public void onAccessUnit(AudioAccessUnit accessUnit) {
            units.add(accessUnit);
            events.add("unit");
            frameLatch.countDown();
        }

        @Override
        public void onUnavailable(AudioCaptureException cause) {
            failure = cause;
            events.add("failure");
            failureLatch.countDown();
        }

        boolean awaitFrames(long timeoutMs) throws InterruptedException {
            return frameLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        }

        boolean awaitFailure(long timeoutMs) throws InterruptedException {
            return failureLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        }

        int firstEventIsDescriptor() {
            synchronized (events) {
                return !events.isEmpty() && "descriptor".equals(events.get(0)) ? 1 : 0;
            }
        }
    }
}
