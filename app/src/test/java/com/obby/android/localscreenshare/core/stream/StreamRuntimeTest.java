package com.obby.android.localscreenshare.core.stream;

import com.obby.android.localscreenshare.core.codec.CodecRegistry;
import com.obby.android.localscreenshare.core.codec.CodecSwitchException;
import com.obby.android.localscreenshare.core.codec.ConfigEpochException;
import com.obby.android.localscreenshare.core.metrics.PipelineMetrics;
import com.obby.android.localscreenshare.core.model.BitstreamFormat;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.model.StreamParameters;
import com.obby.android.localscreenshare.core.support.FakeCodec;
import com.obby.android.localscreenshare.core.support.MutableClock;
import com.obby.android.localscreenshare.core.support.MutableNanoClock;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class StreamRuntimeTest {

    private static final StreamKey STREAM_KEY = new StreamKey("session-1", "stream-1");

    private static final long JOIN_INTERVAL_MS = 100L;

    @Test
    public void metrics_countForwardedUnitsBytesJoinPointsAndRequests() {
        FakeCodec fake = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        fake.setAutoEmitJoinOnRequest(false);
        MutableClock clock = new MutableClock();
        PipelineMetrics metrics = PipelineMetrics.create(new MutableNanoClock(), 8);
        CodecRegistry registry = new CodecRegistry();
        registry.register(fake.registration());
        StreamRuntime runtime = new StreamRuntime(registry, STREAM_KEY, descriptor(1, 64, 48),
                StreamParameters.DEFAULT, clock, JOIN_INTERVAL_MS, metrics);
        runtime.addListener(new RecordingListener());

        runtime.start();
        assertTrue(runtime.requestJoinPoint());
        assertEquals(1L, metrics.count(PipelineMetrics.Counter.JOIN_REQUESTS));

        byte[] payload = new byte[]{0x01, 0x02, 0x03};
        fake.emitJoin(payload);
        fake.emitNonJoin(new byte[]{0x04, 0x05});

        assertEquals(2L, metrics.count(PipelineMetrics.Counter.ACCESS_UNITS));
        assertEquals(5L, metrics.count(PipelineMetrics.Counter.ACCESS_UNIT_BYTES));
        assertEquals(1L, metrics.count(PipelineMetrics.Counter.JOIN_POINTS));
    }

    @Test
    public void start_createsEncoderAndForwardsAccessUnits() {
        FakeCodec fake = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        MutableClock clock = new MutableClock();
        StreamDescriptor descriptor = descriptor(1, 64, 48);
        StreamRuntime runtime = newRuntime(fake, clock, descriptor);
        RecordingListener listener = new RecordingListener();
        runtime.addListener(listener);

        assertFalse(runtime.isClosed());
        runtime.start();

        FakeCodec.FakeEncoder encoder = fake.getLastEncoder();
        assertNotNull(encoder);
        assertTrue(encoder.isStarted());
        assertEquals(1, runtime.getConfigEpoch());
        assertEquals(descriptor.getConfigHash(), runtime.getDescriptor().getConfigHash());
        assertEquals(StreamParameters.DEFAULT.getTargetBitrateBps(),
                runtime.getParameters().getTargetBitrateBps());
        assertNull(runtime.getInputSurface());
        assertFalse(runtime.isClosed());

        byte[] payload = new byte[]{0x01, 0x02, 0x03};
        fake.emitNonJoin(payload);

        assertEquals(1, listener.accessUnits.size());
        EncodedAccessUnit forwarded = listener.accessUnits.get(0);
        assertEquals(1, forwarded.getConfigEpoch());
        assertEquals(0L, forwarded.getSequence());
        assertFalse(forwarded.getFlags().isJoinPoint());
        assertArrayEquals(payload, forwarded.getPayload());
    }

    @Test
    public void getInputSurface_isNullForJpegLikeEncoder() {
        FakeCodec fake = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        StreamRuntime runtime = newRuntime(fake, new MutableClock(), descriptor(1, 64, 48));

        runtime.start();

        assertNull(runtime.getInputSurface());
    }

    @Test
    public void start_surfaceRequiredButEncoderReturnsNull_throws() {
        FakeCodec fake = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, true, false);
        StreamRuntime runtime = newRuntime(fake, new MutableClock(), descriptor(1, 64, 48));

        try {
            runtime.start();
            fail("surface-required codec with a null input surface must not start");
        } catch (IllegalStateException expected) {
            assertNotNull(expected);
        }

        assertNotNull(fake.getLastEncoder());
        assertFalse(fake.getLastEncoder().isStarted());
        assertFalse(runtime.isClosed());
    }

    @Test
    public void updateParameters_doesNotAdvanceEpochOrRecreateEncoder() {
        FakeCodec fake = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        StreamRuntime runtime = newRuntime(fake, new MutableClock(), descriptor(1, 64, 48));
        runtime.start();
        FakeCodec.FakeEncoder encoder = fake.getLastEncoder();
        String beforeHash = runtime.getDescriptor().getConfigHash();

        StreamParameters updated = new StreamParameters(1_500_000, 15, 500);
        runtime.updateParameters(updated);

        assertEquals(1, runtime.getConfigEpoch());
        assertEquals(beforeHash, runtime.getDescriptor().getConfigHash());
        assertSame(encoder, fake.getLastEncoder());
        assertFalse(encoder.isStopped());
        assertEquals(1_500_000, runtime.getParameters().getTargetBitrateBps());
        assertEquals(15, runtime.getParameters().getFrameRateHint());
        assertEquals(500, runtime.getParameters().getKeyFrameIntervalMs());
        assertEquals(1_500_000, encoder.getLastParameters().getTargetBitrateBps());
        assertEquals(15, encoder.getLastParameters().getFrameRateHint());
        assertEquals(500, encoder.getLastParameters().getKeyFrameIntervalMs());
    }

    @Test
    public void requestJoinPoint_coalescesUntilJoinPoint() {
        FakeCodec fake = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        fake.setAutoEmitJoinOnRequest(false);
        MutableClock clock = new MutableClock();
        StreamRuntime runtime = newRuntime(fake, clock, descriptor(1, 64, 48));
        RecordingListener listener = new RecordingListener();
        runtime.addListener(listener);
        runtime.start();

        assertTrue(runtime.requestJoinPoint());
        assertFalse(runtime.requestJoinPoint());
        assertFalse(runtime.requestJoinPoint());
        assertEquals(1, fake.getRequestCount());
        assertTrue(listener.accessUnits.isEmpty());

        fake.emitJoin(new byte[]{0x0A});
        assertEquals(1, listener.accessUnits.size());
        assertTrue(listener.accessUnits.get(0).getFlags().isJoinPoint());

        clock.advance(JOIN_INTERVAL_MS + 1);
        assertTrue(runtime.requestJoinPoint());
        assertEquals(2, fake.getRequestCount());
    }

    @Test
    public void requestJoinPoint_isRateLimited() {
        FakeCodec fake = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        MutableClock clock = new MutableClock();
        StreamRuntime runtime = newRuntime(fake, clock, descriptor(1, 64, 48));
        RecordingListener listener = new RecordingListener();
        runtime.addListener(listener);
        runtime.start();

        assertTrue(runtime.requestJoinPoint());
        assertEquals(1, fake.getRequestCount());
        assertEquals(1, listener.accessUnits.size());

        assertFalse(runtime.requestJoinPoint());
        assertEquals(1, fake.getRequestCount());
        assertEquals(1, listener.accessUnits.size());

        clock.advance(JOIN_INTERVAL_MS + 1);
        assertTrue(runtime.requestJoinPoint());
        assertEquals(2, fake.getRequestCount());
        assertEquals(2, listener.accessUnits.size());
    }

    @Test
    public void switchDescriptor_advancesEpochAndReplacesEncoder() {
        FakeCodec fake = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        MutableClock clock = new MutableClock();
        StreamRuntime runtime = newRuntime(fake, clock, descriptor(1, 64, 48));
        RecordingListener listener = new RecordingListener();
        runtime.addListener(listener);
        runtime.start();
        FakeCodec.FakeEncoder oldEncoder = fake.getLastEncoder();

        StreamDescriptor next = descriptor(2, 32, 24);
        runtime.switchDescriptor(next);

        FakeCodec.FakeEncoder newEncoder = fake.getLastEncoder();
        assertNotSame(oldEncoder, newEncoder);
        assertTrue(oldEncoder.isStopped());
        assertTrue(oldEncoder.isClosed());
        assertTrue(newEncoder.isStarted());
        assertEquals(1, newEncoder.getRequestCount());
        assertEquals(2, runtime.getConfigEpoch());
        assertEquals(next.getConfigHash(), runtime.getDescriptor().getConfigHash());
        assertFalse(listener.descriptors.isEmpty());
        assertEquals(2, listener.descriptors.get(listener.descriptors.size() - 1).getConfigEpoch());

        int descriptorIndex = listener.events.indexOf("descriptor:2");
        int accessUnitIndex = listener.events.indexOf("au:2:0");
        assertTrue(descriptorIndex >= 0);
        assertTrue(accessUnitIndex > descriptorIndex);
        assertEquals(1, listener.accessUnits.size());
        EncodedAccessUnit join = listener.accessUnits.get(0);
        assertEquals(2, join.getConfigEpoch());
        assertEquals(0L, join.getSequence());
        assertTrue(join.getFlags().isJoinPoint());
    }

    @Test
    public void switchDescriptor_rejectsDuplicateAndSkippedEpoch() {
        FakeCodec fake = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        StreamRuntime runtime = newRuntime(fake, new MutableClock(), descriptor(1, 64, 48));
        runtime.start();
        FakeCodec.FakeEncoder encoder = fake.getLastEncoder();

        try {
            runtime.switchDescriptor(descriptor(1, 64, 48));
            fail("duplicate epoch must be rejected");
        } catch (ConfigEpochException expected) {
            assertNotNull(expected);
        }

        try {
            runtime.switchDescriptor(descriptor(3, 32, 24));
            fail("skipped epoch must be rejected");
        } catch (ConfigEpochException expected) {
            assertNotNull(expected);
        }

        assertEquals(1, runtime.getConfigEpoch());
        assertSame(encoder, fake.getLastEncoder());
        assertFalse(encoder.isStopped());
    }

    @Test
    public void switchDescriptor_allowsEpochOnlyAdvance() {
        FakeCodec fake = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        StreamRuntime runtime = newRuntime(fake, new MutableClock(), descriptor(1, 64, 48));
        runtime.start();
        FakeCodec.FakeEncoder oldEncoder = fake.getLastEncoder();

        runtime.switchDescriptor(descriptor(1, 64, 48).withConfigEpoch(2));

        FakeCodec.FakeEncoder newEncoder = fake.getLastEncoder();
        assertNotSame(oldEncoder, newEncoder);
        assertEquals(2, runtime.getConfigEpoch());
        assertEquals(2, runtime.getDescriptor().getConfigEpoch());
        assertTrue(oldEncoder.isStopped());
    }

    @Test
    public void switchDescriptor_afterUpdateParameters_rebuildsWithNewParameters() {
        FakeCodec fake = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        StreamRuntime runtime = newRuntime(fake, new MutableClock(), descriptor(1, 64, 48));
        runtime.start();
        FakeCodec.FakeEncoder oldEncoder = fake.getLastEncoder();

        // Same size, new rate and cadence hints: the rebuild must hand the new hints to the new
        // encoder, because the encoder only reads them when it is created.
        StreamParameters updated = new StreamParameters(2_500_000, 30, 2_000);
        runtime.updateParameters(updated);
        runtime.switchDescriptor(descriptor(1, 64, 48).withConfigEpoch(2));

        FakeCodec.FakeEncoder newEncoder = fake.getLastEncoder();
        assertNotSame(oldEncoder, newEncoder);
        assertEquals(2_500_000, newEncoder.getLastParameters().getTargetBitrateBps());
        assertEquals(30, newEncoder.getLastParameters().getFrameRateHint());
        assertEquals(2_000, newEncoder.getLastParameters().getKeyFrameIntervalMs());
        assertEquals(2, runtime.getConfigEpoch());
        assertEquals(updated.getTargetBitrateBps(), runtime.getParameters().getTargetBitrateBps());
    }

    @Test
    public void switchDescriptor_prewarmFailureKeepsOldEncoder() {
        FakeCodec fake = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        StreamRuntime runtime = newRuntime(fake, new MutableClock(), descriptor(1, 64, 48));
        RecordingListener listener = new RecordingListener();
        runtime.addListener(listener);
        runtime.start();
        FakeCodec.FakeEncoder oldEncoder = fake.getLastEncoder();

        fake.failNextEncoderCreate();
        try {
            runtime.switchDescriptor(descriptor(2, 32, 24));
            fail("encoder creation failure must abort the switch");
        } catch (CodecSwitchException expected) {
            assertNotNull(expected.getCause());
        }

        assertEquals(1, runtime.getConfigEpoch());
        assertSame(oldEncoder, fake.getLastEncoder());
        assertFalse(oldEncoder.isStopped());
        assertFalse(oldEncoder.isClosed());
        assertFalse(runtime.isClosed());

        byte[] payload = new byte[]{0x0B};
        fake.emitNonJoin(payload);
        assertEquals(1, listener.accessUnits.size());
        assertEquals(1, listener.accessUnits.get(0).getConfigEpoch());
        assertArrayEquals(payload, listener.accessUnits.get(0).getPayload());
    }

    @Test
    public void staleEncoderOrdinaryAu_isDroppedAfterSwitch() {
        FakeCodec fake = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        fake.setAutoEmitJoinOnRequest(false);
        StreamRuntime runtime = newRuntime(fake, new MutableClock(), descriptor(1, 64, 48));
        RecordingListener listener = new RecordingListener();
        runtime.addListener(listener);
        runtime.start();
        FakeCodec.FakeEncoder oldEncoder = fake.getLastEncoder();

        runtime.switchDescriptor(descriptor(2, 32, 24));
        FakeCodec.FakeEncoder currentEncoder = fake.getLastEncoder();
        assertNotSame(oldEncoder, currentEncoder);
        assertFalse(oldEncoder.isClosed());
        assertTrue(listener.accessUnits.isEmpty());

        oldEncoder.emit(1, false, new byte[]{0x11});
        oldEncoder.emit(2, false, new byte[]{0x12});
        assertTrue(listener.accessUnits.isEmpty());

        byte[] payload = new byte[]{0x13};
        currentEncoder.emitNonJoin(payload);
        assertEquals(1, listener.accessUnits.size());
        assertEquals(2, listener.accessUnits.get(0).getConfigEpoch());
        assertArrayEquals(payload, listener.accessUnits.get(0).getPayload());
    }

    @Test
    public void oldEpochOrdinaryAu_isDroppedByRuntime() {
        FakeCodec fake = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        fake.setAutoEmitJoinOnRequest(false);
        StreamRuntime runtime = newRuntime(fake, new MutableClock(), descriptor(1, 64, 48));
        RecordingListener listener = new RecordingListener();
        runtime.addListener(listener);
        runtime.start();

        runtime.switchDescriptor(descriptor(2, 32, 24));
        FakeCodec.FakeEncoder currentEncoder = fake.getLastEncoder();
        currentEncoder.emit(1, false, new byte[]{0x21});

        assertTrue(listener.accessUnits.isEmpty());
        assertEquals(2, runtime.getConfigEpoch());
    }

    @Test
    public void newEpochSequenceStartsAtZero() {
        FakeCodec fake = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        fake.setAutoEmitJoinOnRequest(false);
        StreamRuntime runtime = newRuntime(fake, new MutableClock(), descriptor(1, 64, 48));
        RecordingListener listener = new RecordingListener();
        runtime.addListener(listener);
        runtime.start();

        runtime.switchDescriptor(descriptor(2, 32, 24));
        fake.emitJoin(new byte[]{0x31});

        assertEquals(1, listener.accessUnits.size());
        EncodedAccessUnit join = listener.accessUnits.get(0);
        assertEquals(2, join.getConfigEpoch());
        assertEquals(0L, join.getSequence());
        assertTrue(join.getFlags().isJoinPoint());
    }

    @Test
    public void listenerException_doesNotStopOtherListeners() {
        FakeCodec fake = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        StreamRuntime runtime = newRuntime(fake, new MutableClock(), descriptor(1, 64, 48));
        RecordingListener recording = new RecordingListener();
        runtime.addListener(new StreamRuntimeListener() {
            @Override
            public void onAccessUnit(EncodedAccessUnit accessUnit) {
                throw new IllegalStateException("listener failure");
            }

            @Override
            public void onDescriptorChanged(StreamDescriptor descriptor) {
                throw new IllegalStateException("listener failure");
            }
        });
        runtime.addListener(recording);
        runtime.start();

        fake.emitNonJoin(new byte[]{0x41});

        assertEquals(1, recording.accessUnits.size());
        assertFalse(runtime.isClosed());
    }

    @Test
    public void close_isIdempotentAndClosesActiveAndRetiredEncoders() {
        FakeCodec fake = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        fake.setAutoEmitJoinOnRequest(false);
        StreamRuntime runtime = newRuntime(fake, new MutableClock(), descriptor(1, 64, 48));
        runtime.start();
        FakeCodec.FakeEncoder retiredEncoder = fake.getLastEncoder();

        runtime.switchDescriptor(descriptor(2, 32, 24));
        FakeCodec.FakeEncoder activeEncoder = fake.getLastEncoder();
        assertNotSame(retiredEncoder, activeEncoder);
        assertTrue(retiredEncoder.isStopped());
        assertFalse(retiredEncoder.isClosed());

        runtime.close();

        assertTrue(runtime.isClosed());
        assertTrue(retiredEncoder.isClosed());
        assertTrue(activeEncoder.isClosed());

        runtime.close();

        assertTrue(runtime.isClosed());
    }

    @Test
    public void close_switchDescriptorRejectedWithoutCreatingEncoder() {
        FakeCodec fake = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        StreamRuntime runtime = newRuntime(fake, new MutableClock(), descriptor(1, 64, 48));
        runtime.start();
        FakeCodec.FakeEncoder activeEncoder = fake.getLastEncoder();
        int createsBeforeClose = fake.getEncoderCreateCount();
        runtime.close();

        try {
            runtime.switchDescriptor(descriptor(2, 32, 24));
            fail("switchDescriptor after close must be rejected");
        } catch (IllegalStateException expected) {
            assertNotNull(expected);
        }

        assertEquals(createsBeforeClose, fake.getEncoderCreateCount());
        assertSame(activeEncoder, fake.getLastEncoder());
        assertEquals(1, runtime.getConfigEpoch());
        assertTrue(runtime.isClosed());
    }

    @Test
    public void close_updateParametersRejectedWithoutChangingState() {
        FakeCodec fake = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        StreamRuntime runtime = newRuntime(fake, new MutableClock(), descriptor(1, 64, 48));
        runtime.start();
        FakeCodec.FakeEncoder activeEncoder = fake.getLastEncoder();
        StreamParameters before = runtime.getParameters();
        runtime.close();

        try {
            runtime.updateParameters(new StreamParameters(123_000, 7, 250));
            fail("updateParameters after close must be rejected");
        } catch (IllegalStateException expected) {
            assertNotNull(expected);
        }

        assertEquals(before.getTargetBitrateBps(),
                runtime.getParameters().getTargetBitrateBps());
        assertEquals(before.getFrameRateHint(), runtime.getParameters().getFrameRateHint());
        assertEquals(before.getKeyFrameIntervalMs(),
                runtime.getParameters().getKeyFrameIntervalMs());
        assertEquals(before.getTargetBitrateBps(),
                activeEncoder.getLastParameters().getTargetBitrateBps());
    }

    @Test
    public void requestJoinPoint_syncPointFailureRollsBackPendingAndAllowsRetry() {
        FakeCodec fake = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        fake.setAutoEmitJoinOnRequest(false);
        StreamRuntime runtime = newRuntime(fake, new MutableClock(), descriptor(1, 64, 48));
        RecordingListener listener = new RecordingListener();
        runtime.addListener(listener);
        runtime.start();

        fake.failNextRequestSyncPoint();
        try {
            runtime.requestJoinPoint();
            fail("requestSyncPoint failure must propagate");
        } catch (IllegalStateException expected) {
            assertNotNull(expected);
        }
        assertEquals(1, fake.getRequestCount());

        assertTrue(runtime.requestJoinPoint());
        assertEquals(2, fake.getRequestCount());
        assertTrue(listener.accessUnits.isEmpty());
    }

    @Test
    public void requestJoinPoint_staleSyncPointFailureDoesNotClearNewEpochPending() {
        FakeCodec fake = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        fake.setAutoEmitJoinOnRequest(false);
        StreamRuntime runtime = newRuntime(fake, new MutableClock(), descriptor(1, 64, 48));
        runtime.start();
        FakeCodec.FakeEncoder oldEncoder = fake.getLastEncoder();

        final StreamDescriptor next = descriptor(2, 32, 24);
        fake.failNextRequestSyncPoint(() -> runtime.switchDescriptor(next));
        try {
            runtime.requestJoinPoint();
            fail("requestSyncPoint failure must propagate");
        } catch (IllegalStateException expected) {
            assertNotNull(expected);
        }

        FakeCodec.FakeEncoder currentEncoder = fake.getLastEncoder();
        assertNotSame(oldEncoder, currentEncoder);
        assertEquals(2, runtime.getConfigEpoch());
        assertTrue(oldEncoder.isStopped());
        assertEquals(1, currentEncoder.getRequestCount());

        assertFalse(runtime.requestJoinPoint());
        assertEquals(1, currentEncoder.getRequestCount());
    }

    @Test
    public void start_surfaceValidationFailure_closesRejectedEncoder() {
        FakeCodec fake = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, true, false);
        StreamRuntime runtime = newRuntime(fake, new MutableClock(), descriptor(1, 64, 48));

        try {
            runtime.start();
            fail("surface validation failure must abort start");
        } catch (IllegalStateException expected) {
            assertNotNull(expected);
        }

        FakeCodec.FakeEncoder encoder = fake.getLastEncoder();
        assertNotNull(encoder);
        assertFalse(encoder.isStarted());
        assertTrue(encoder.isClosed());
        assertEquals(1, fake.getEncoderCreateCount());
        assertNull(runtime.getInputSurface());
    }

    @Test
    public void start_encoderStartFailure_closesEncoder() {
        FakeCodec fake = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        StreamRuntime runtime = newRuntime(fake, new MutableClock(), descriptor(1, 64, 48));
        fake.failNextEncoderStart();

        try {
            runtime.start();
            fail("encoder start failure must propagate");
        } catch (IllegalStateException expected) {
            assertNotNull(expected);
        }

        FakeCodec.FakeEncoder encoder = fake.getLastEncoder();
        assertNotNull(encoder);
        assertFalse(encoder.isStarted());
        assertTrue(encoder.isClosed());
        assertNull(runtime.getInputSurface());
    }

    @Test
    public void startFailureThenRetry_dropsLateAccessUnitFromFailedAttempt() {
        FakeCodec fake = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        fake.setAutoEmitJoinOnRequest(false);
        StreamRuntime runtime = newRuntime(fake, new MutableClock(), descriptor(1, 64, 48));
        RecordingListener listener = new RecordingListener();
        runtime.addListener(listener);

        fake.failNextEncoderStart();
        try {
            runtime.start();
            fail("encoder start failure must propagate");
        } catch (IllegalStateException expected) {
            assertNotNull(expected);
        }
        FakeCodec.FakeEncoder failedAttempt = fake.getLastEncoder();
        assertNotNull(failedAttempt);
        assertTrue(failedAttempt.isClosed());

        runtime.start();
        FakeCodec.FakeEncoder active = fake.getLastEncoder();
        assertNotSame(failedAttempt, active);
        assertTrue(active.isStarted());

        failedAttempt.emit(1, false, new byte[]{0x61});
        failedAttempt.emit(1, true, new byte[]{0x62});

        assertTrue("late AUs from the failed start attempt must not be forwarded",
                listener.accessUnits.isEmpty());

        byte[] payload = new byte[]{0x63};
        active.emitNonJoin(payload);
        assertEquals(1, listener.accessUnits.size());
        assertArrayEquals(payload, listener.accessUnits.get(0).getPayload());
    }

    @Test
    public void switchDescriptor_surfaceValidationFailure_closesNewEncoderAndKeepsOld() {
        FakeCodec oldFake = new FakeCodec("fake-old", 0, false, false);
        FakeCodec newFake = new FakeCodec("fake-new", 0, true, false);
        CodecRegistry registry = new CodecRegistry();
        registry.register(oldFake.registration());
        registry.register(newFake.registration());
        StreamDescriptor oldDescriptor = descriptor(1, "fake-old", 64, 48);
        StreamRuntime runtime = newRuntime(registry, new MutableClock(), oldDescriptor);
        RecordingListener listener = new RecordingListener();
        runtime.addListener(listener);
        runtime.start();
        FakeCodec.FakeEncoder oldEncoder = oldFake.getLastEncoder();

        try {
            runtime.switchDescriptor(descriptor(2, "fake-new", 32, 24));
            fail("surface validation failure must abort the switch");
        } catch (CodecSwitchException expected) {
            assertNotNull(expected.getCause());
        }

        FakeCodec.FakeEncoder rejectedEncoder = newFake.getLastEncoder();
        assertNotNull(rejectedEncoder);
        assertFalse(rejectedEncoder.isStarted());
        assertTrue(rejectedEncoder.isClosed());
        assertSame(oldEncoder, oldFake.getLastEncoder());
        assertFalse(oldEncoder.isStopped());
        assertFalse(oldEncoder.isClosed());
        assertEquals(1, runtime.getConfigEpoch());
        assertEquals(oldDescriptor.getConfigHash(), runtime.getDescriptor().getConfigHash());

        byte[] payload = new byte[]{0x51};
        oldEncoder.emitNonJoin(payload);
        assertEquals(1, listener.accessUnits.size());
        assertArrayEquals(payload, listener.accessUnits.get(0).getPayload());
    }

    @Test
    public void switchDescriptor_newEncoderStartFailure_closesNewAndKeepsOld() {
        FakeCodec fake = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        StreamRuntime runtime = newRuntime(fake, new MutableClock(), descriptor(1, 64, 48));
        RecordingListener listener = new RecordingListener();
        runtime.addListener(listener);
        runtime.start();
        FakeCodec.FakeEncoder oldEncoder = fake.getLastEncoder();
        fake.failNextEncoderStart();

        try {
            runtime.switchDescriptor(descriptor(2, 32, 24));
            fail("encoder start failure must abort the switch");
        } catch (CodecSwitchException expected) {
            assertNotNull(expected.getCause());
        }

        FakeCodec.FakeEncoder rejectedEncoder = fake.getLastEncoder();
        assertNotSame(oldEncoder, rejectedEncoder);
        assertFalse(rejectedEncoder.isStarted());
        assertTrue(rejectedEncoder.isClosed());
        assertFalse(oldEncoder.isStopped());
        assertFalse(oldEncoder.isClosed());
        assertEquals(1, runtime.getConfigEpoch());

        byte[] payload = new byte[]{0x52};
        oldEncoder.emitNonJoin(payload);
        assertEquals(1, listener.accessUnits.size());
        assertArrayEquals(payload, listener.accessUnits.get(0).getPayload());
    }

    @Test
    public void switchDescriptor_stopsTheOldEncoderOutsideTheRuntimeLock() {
        FakeCodec fake = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        StreamRuntime runtime = newRuntime(fake, new MutableClock(), descriptor(1, 64, 48));
        runtime.start();
        FakeCodec.FakeEncoder oldEncoder = fake.getLastEncoder();

        // The deadlock was structural: the switch held the runtime lock while stopping the old
        // encoder, so the drain thread could not finish the access unit it was forwarding. The
        // probe asks the runtime for its state from a second thread during stop() and reports
        // whether that thread got through, which is exactly what the drain thread needs.
        AtomicBoolean lockFreeDuringStop = new AtomicBoolean(false);
        oldEncoder.setOnStop(() -> lockFreeDuringStop.set(
                canReadRuntimeStateFromAnotherThread(runtime)));

        runtime.switchDescriptor(descriptor(2, 32, 24));

        assertTrue("the superseded encoder must be stopped without holding the runtime lock",
                lockFreeDuringStop.get());
        assertEquals(2, runtime.getConfigEpoch());
    }

    /**
     * Returns whether another thread can read the runtime state while the caller is inside an
     * encoder lifecycle call; a bounded join turns the answer into a boolean instead of a hang.
     */
    private static boolean canReadRuntimeStateFromAnotherThread(StreamRuntime runtime) {
        Thread probe = new Thread(runtime::getState, "runtime-lock-probe");
        probe.setDaemon(true);
        probe.start();
        try {
            probe.join(500L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return !probe.isAlive();
    }

    @Test
    public void switchDescriptor_oldStopFailure_stillCommitsTheNewEncoder() {
        FakeCodec fake = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        fake.setAutoEmitJoinOnRequest(false);
        StreamRuntime runtime = newRuntime(fake, new MutableClock(), descriptor(1, 64, 48));
        RecordingListener listener = new RecordingListener();
        runtime.addListener(listener);
        runtime.start();
        FakeCodec.FakeEncoder oldEncoder = fake.getLastEncoder();
        fake.failNextStop();

        // A stop failure must not abort the switch: the old encoder has already been told to stop,
        // so keeping it active would leave the runtime bound to a dead encoder and the capture
        // display pointing at a dead surface. The switch commits and the old encoder is retired.
        runtime.switchDescriptor(descriptor(2, 32, 24));

        FakeCodec.FakeEncoder newEncoder = fake.getLastEncoder();
        assertNotSame(oldEncoder, newEncoder);
        assertTrue(newEncoder.isStarted());
        assertFalse(newEncoder.isClosed());
        assertFalse(oldEncoder.isStopped());
        assertEquals(2, runtime.getConfigEpoch());
        assertEquals(1, newEncoder.getRequestCount());

        // Frames from the superseded encoder are dropped; the new epoch keeps the stream alive.
        byte[] stalePayload = new byte[]{0x53};
        byte[] newPayload = new byte[]{0x54};
        oldEncoder.emitNonJoin(stalePayload);
        newEncoder.emitNonJoin(newPayload);
        assertEquals(1, listener.accessUnits.size());
        assertArrayEquals(newPayload, listener.accessUnits.get(0).getPayload());
        assertEquals(2, listener.accessUnits.get(0).getConfigEpoch());
    }

    @Test
    public void switchDescriptor_afterOldStopFailure_keepsAdvancingEpochs() {
        FakeCodec fake = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        fake.setAutoEmitJoinOnRequest(false);
        StreamRuntime runtime = newRuntime(fake, new MutableClock(), descriptor(1, 64, 48));
        RecordingListener listener = new RecordingListener();
        runtime.addListener(listener);
        runtime.start();
        FakeCodec.FakeEncoder firstEncoder = fake.getLastEncoder();
        fake.failNextStop();

        // The device symptom was "the first switch fails and the stream stays dead": the retry has
        // to work without restarting the capture, so a second switch must be accepted as well.
        runtime.switchDescriptor(descriptor(2, 32, 24));
        FakeCodec.FakeEncoder secondEncoder = fake.getLastEncoder();
        runtime.switchDescriptor(descriptor(3, 64, 48));
        FakeCodec.FakeEncoder thirdEncoder = fake.getLastEncoder();

        assertNotSame(firstEncoder, secondEncoder);
        assertNotSame(secondEncoder, thirdEncoder);
        assertEquals(3, runtime.getConfigEpoch());
        assertTrue(thirdEncoder.isStarted());
        assertFalse(runtime.isClosed());

        byte[] payload = new byte[]{0x55};
        thirdEncoder.emitNonJoin(payload);
        assertEquals(1, listener.accessUnits.size());
        assertEquals(3, listener.accessUnits.get(0).getConfigEpoch());
        assertArrayEquals(payload, listener.accessUnits.get(0).getPayload());
    }

    @Test
    public void switchPrewarmFailureThenRetry_dropsLateAccessUnitFromFailedAttempt() {
        FakeCodec fake = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        fake.setAutoEmitJoinOnRequest(false);
        StreamRuntime runtime = newRuntime(fake, new MutableClock(), descriptor(1, 64, 48));
        RecordingListener listener = new RecordingListener();
        runtime.addListener(listener);
        runtime.start();
        FakeCodec.FakeEncoder oldActive = fake.getLastEncoder();

        fake.failNextEncoderStart();
        try {
            runtime.switchDescriptor(descriptor(2, 32, 24));
            fail("encoder start failure must abort the switch");
        } catch (CodecSwitchException expected) {
            assertNotNull(expected.getCause());
        }
        FakeCodec.FakeEncoder failedAttempt = fake.getLastEncoder();
        assertNotSame(oldActive, failedAttempt);
        assertTrue(failedAttempt.isClosed());
        assertFalse(oldActive.isStopped());

        byte[] oldPayload = new byte[]{0x71};
        oldActive.emitNonJoin(oldPayload);
        assertEquals(1, listener.accessUnits.size());
        assertArrayEquals(oldPayload, listener.accessUnits.get(0).getPayload());

        runtime.switchDescriptor(descriptor(2, 32, 24));
        FakeCodec.FakeEncoder active = fake.getLastEncoder();
        assertNotSame(failedAttempt, active);
        assertTrue(active.isStarted());
        assertEquals(2, runtime.getConfigEpoch());

        int before = listener.accessUnits.size();
        failedAttempt.emit(2, false, new byte[]{0x72});
        failedAttempt.emit(2, true, new byte[]{0x73});
        assertEquals("late AUs from the failed prewarm attempt must not be forwarded",
                before, listener.accessUnits.size());

        byte[] activePayload = new byte[]{0x74};
        active.emitNonJoin(activePayload);
        assertEquals(before + 1, listener.accessUnits.size());
        assertArrayEquals(activePayload,
                listener.accessUnits.get(listener.accessUnits.size() - 1).getPayload());
    }

    @Test
    public void switchOldStopFailure_retiresOldEncoderAndDropsItsLateUnits() {
        FakeCodec fake = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        fake.setAutoEmitJoinOnRequest(false);
        StreamRuntime runtime = newRuntime(fake, new MutableClock(), descriptor(1, 64, 48));
        RecordingListener listener = new RecordingListener();
        runtime.addListener(listener);
        runtime.start();
        FakeCodec.FakeEncoder oldActive = fake.getLastEncoder();

        fake.failNextStop();
        runtime.switchDescriptor(descriptor(2, 32, 24));
        FakeCodec.FakeEncoder active = fake.getLastEncoder();
        assertNotSame(oldActive, active);
        assertTrue(active.isStarted());
        assertFalse(active.isClosed());
        assertEquals(2, runtime.getConfigEpoch());

        // Late units of the superseded encoder belong to the previous attempt generation and must
        // not reach the current epoch, even though its stop() failed.
        int before = listener.accessUnits.size();
        oldActive.emit(1, false, new byte[]{0x42});
        oldActive.emit(1, true, new byte[]{0x43});
        assertEquals("late AUs from the superseded encoder must not be forwarded",
                before, listener.accessUnits.size());

        byte[] activePayload = new byte[]{0x44};
        active.emitNonJoin(activePayload);
        assertEquals(before + 1, listener.accessUnits.size());
        assertArrayEquals(activePayload,
                listener.accessUnits.get(listener.accessUnits.size() - 1).getPayload());

        // The retired encoder is closed on the first join point of the new epoch, which is also the
        // point by which the owner has re-pointed the capture display at the new input surface.
        assertFalse(oldActive.isClosed());
        active.emit(2, true, new byte[]{0x45});
        assertTrue(oldActive.isClosed());
        assertEquals(before + 2, listener.accessUnits.size());
    }

    private static StreamRuntime newRuntime(FakeCodec fake, MutableClock clock,
                                            StreamDescriptor descriptor) {
        CodecRegistry registry = new CodecRegistry();
        registry.register(fake.registration());
        return newRuntime(registry, clock, descriptor);
    }

    private static StreamRuntime newRuntime(CodecRegistry registry, MutableClock clock,
                                            StreamDescriptor descriptor) {
        return new StreamRuntime(registry, STREAM_KEY, descriptor, StreamParameters.DEFAULT,
                clock, JOIN_INTERVAL_MS);
    }

    private static StreamDescriptor descriptor(int configEpoch, int width, int height) {
        return descriptor(configEpoch, FakeCodec.DEFAULT_CODEC_ID, width, height);
    }

    private static StreamDescriptor descriptor(int configEpoch, String codecId,
                                               int width, int height) {
        return StreamDescriptor.builder(STREAM_KEY, configEpoch, codecId,
                FakeCodec.DEFAULT_MIME, BitstreamFormat.ANNEX_B)
                .codedSize(width, height)
                .visibleSize(width, height)
                .build();
    }

    private static final class RecordingListener implements StreamRuntimeListener {

        private final List<String> events = new ArrayList<>();

        private final List<EncodedAccessUnit> accessUnits = new ArrayList<>();

        private final List<StreamDescriptor> descriptors = new ArrayList<>();

        @Override
        public void onAccessUnit(EncodedAccessUnit accessUnit) {
            events.add("au:" + accessUnit.getConfigEpoch() + ":" + accessUnit.getSequence());
            accessUnits.add(accessUnit);
        }

        @Override
        public void onDescriptorChanged(StreamDescriptor descriptor) {
            events.add("descriptor:" + descriptor.getConfigEpoch());
            descriptors.add(descriptor);
        }
    }
}
