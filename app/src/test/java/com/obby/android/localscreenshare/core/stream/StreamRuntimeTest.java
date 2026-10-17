package com.obby.android.localscreenshare.core.stream;

import com.obby.android.localscreenshare.core.codec.CodecRegistry;
import com.obby.android.localscreenshare.core.codec.CodecSwitchException;
import com.obby.android.localscreenshare.core.codec.ConfigEpochException;
import com.obby.android.localscreenshare.core.model.BitstreamFormat;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.model.StreamParameters;
import com.obby.android.localscreenshare.core.support.FakeCodec;
import com.obby.android.localscreenshare.core.support.MutableClock;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

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
    public void switchDescriptor_rejectsUnchangedDecodeConfig() {
        FakeCodec fake = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        StreamRuntime runtime = newRuntime(fake, new MutableClock(), descriptor(1, 64, 48));
        runtime.start();
        FakeCodec.FakeEncoder encoder = fake.getLastEncoder();

        try {
            runtime.switchDescriptor(descriptor(1, 64, 48).withConfigEpoch(2));
            fail("unchanged decode config must be rejected");
        } catch (ConfigEpochException e) {
            fail("unchanged config must not be reported as an epoch mismatch");
        } catch (IllegalArgumentException expected) {
            assertFalse(expected instanceof ConfigEpochException);
        }

        assertEquals(1, runtime.getConfigEpoch());
        assertSame(encoder, fake.getLastEncoder());
        assertFalse(encoder.isStopped());
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

    private static StreamRuntime newRuntime(FakeCodec fake, MutableClock clock,
                                            StreamDescriptor descriptor) {
        CodecRegistry registry = new CodecRegistry();
        registry.register(fake.registration());
        return new StreamRuntime(registry, STREAM_KEY, descriptor, StreamParameters.DEFAULT,
                clock, JOIN_INTERVAL_MS);
    }

    private static StreamDescriptor descriptor(int configEpoch, int width, int height) {
        return StreamDescriptor.builder(STREAM_KEY, configEpoch, FakeCodec.DEFAULT_CODEC_ID,
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
