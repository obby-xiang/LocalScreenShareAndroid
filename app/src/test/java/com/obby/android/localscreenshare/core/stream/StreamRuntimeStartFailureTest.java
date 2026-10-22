package com.obby.android.localscreenshare.core.stream;

import com.obby.android.localscreenshare.core.codec.CodecNotFoundException;
import com.obby.android.localscreenshare.core.codec.CodecRegistry;
import com.obby.android.localscreenshare.core.codec.CodecSwitchException;
import com.obby.android.localscreenshare.core.codec.EncoderException;
import com.obby.android.localscreenshare.core.model.BitstreamFormat;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.model.StreamParameters;
import com.obby.android.localscreenshare.core.support.FakeCodec;
import com.obby.android.localscreenshare.core.support.FakeDeferredCodec;
import com.obby.android.localscreenshare.core.support.MutableClock;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Covers the start failure exit of {@link StreamRuntime} and the cases that used to strand an
 * attempt in {@code CONFIGURING}.
 */
public class StreamRuntimeStartFailureTest {

    private static final StreamKey STREAM_KEY = new StreamKey("session-1", "stream-1");

    private static final long JOIN_INTERVAL_MS = 100L;

    @Test
    public void start_unregisteredCodec_failsAttemptAndKeepsRuntimeRetryable() {
        CodecRegistry registry = new CodecRegistry();
        StreamRuntime runtime = newRuntime(registry,
                descriptor(1, "missing-codec", FakeCodec.DEFAULT_MIME, 640, 360));
        RecordingListener listener = new RecordingListener();
        runtime.addListener(listener);

        try {
            runtime.start();
            fail("an unregistered codec must fail the start");
        } catch (CodecNotFoundException expected) {
            assertEquals("missing-codec", expected.getCodecId());
        }

        // The defect this test pins: the attempt used to stay CONFIGURING, which made the runtime
        // unretryable and left it only close() as an exit.
        assertEquals(RuntimeState.FAILED, runtime.getState());
        assertNull(runtime.getDescriptor());
        assertNull(runtime.getInputSurface());
        assertFalse(runtime.isClosed());
        assertEquals(1, listener.failures.size());
        assertSame(CodecNotFoundException.class, listener.failures.get(0).getCause().getClass());
    }

    @Test
    public void start_afterRegisteringTheMissingCodec_succeeds() {
        CodecRegistry registry = new CodecRegistry();
        FakeCodec fake = new FakeCodec("late-codec", 0, false, false);
        StreamRuntime runtime = newRuntime(registry,
                descriptor(1, "late-codec", FakeCodec.DEFAULT_MIME, 640, 360));

        try {
            runtime.start();
            fail("an unregistered codec must fail the start");
        } catch (CodecNotFoundException expected) {
            assertNotNull(expected);
        }
        assertEquals(RuntimeState.FAILED, runtime.getState());

        registry.register(fake.registration());
        runtime.start();

        assertEquals(RuntimeState.READY, runtime.getState());
        assertNotNull(runtime.getDescriptor());
        assertEquals("late-codec", runtime.getDescriptor().getCodecId());
        assertEquals(1, fake.getEncoderCreateCount());
        assertTrue(fake.getLastEncoder().isStarted());
    }

    @Test
    public void start_previousEncoderCloseFailure_failsAttemptAndKeepsRuntimeRetryable() {
        FakeDeferredCodec codec = new FakeDeferredCodec();
        CodecRegistry registry = new CodecRegistry();
        registry.register(codec.registration());
        StreamRuntime runtime = newRuntime(registry,
                descriptor(1, FakeDeferredCodec.DEFAULT_CODEC_ID,
                        FakeDeferredCodec.DEFAULT_MIME, 640, 360));
        RecordingListener listener = new RecordingListener();
        runtime.addListener(listener);

        runtime.start();
        FakeDeferredCodec.FakeEncoder failedAttempt = codec.getLastEncoder();
        failedAttempt.fail(new EncoderException("injected deferred encoder failure"));
        assertEquals(RuntimeState.FAILED, runtime.getState());
        assertNull(runtime.getDescriptor());

        AtomicBoolean closeFailedOnce = new AtomicBoolean(false);
        failedAttempt.setCloseProbe(() -> {
            if (closeFailedOnce.compareAndSet(false, true)) {
                throw new IllegalStateException("injected close failure");
            }
        });

        try {
            runtime.start();
            fail("a failing close of the previous encoder must fail the retry");
        } catch (EncoderException expected) {
            assertNotNull(expected.getCause());
        }

        assertEquals(RuntimeState.FAILED, runtime.getState());
        assertNull(runtime.getDescriptor());
        assertEquals(1, codec.getEncoderCreateCount());
        assertEquals(2, listener.failures.size());

        // The failed close is not fatal: the encoder is retired and the next attempt still starts.
        runtime.start();
        assertEquals(2, codec.getEncoderCreateCount());
        assertEquals(RuntimeState.CONFIGURING, runtime.getState());
    }

    @Test
    public void start_whenReady_isRejectedWithoutChangingState() {
        FakeCodec fake = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        CodecRegistry registry = new CodecRegistry();
        registry.register(fake.registration());
        StreamRuntime runtime = newRuntime(registry,
                descriptor(1, FakeCodec.DEFAULT_CODEC_ID, FakeCodec.DEFAULT_MIME, 640, 360));
        runtime.start();
        StreamDescriptor published = runtime.getDescriptor();

        try {
            runtime.start();
            fail("a ready runtime must not start again");
        } catch (IllegalStateException expected) {
            assertNotNull(expected);
        }

        assertEquals(RuntimeState.READY, runtime.getState());
        assertSame(published, runtime.getDescriptor());
        assertEquals(1, fake.getEncoderCreateCount());
    }

    @Test
    public void switchDescriptor_unregisteredCodec_keepsOldEncoderAndEpoch() {
        FakeCodec fake = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        CodecRegistry registry = new CodecRegistry();
        registry.register(fake.registration());
        StreamDescriptor first = descriptor(1, FakeCodec.DEFAULT_CODEC_ID,
                FakeCodec.DEFAULT_MIME, 640, 360);
        StreamRuntime runtime = newRuntime(registry, first);
        RecordingListener listener = new RecordingListener();
        runtime.addListener(listener);
        runtime.start();
        FakeCodec.FakeEncoder oldEncoder = fake.getLastEncoder();

        try {
            runtime.switchDescriptor(descriptor(2, "missing-codec", FakeCodec.DEFAULT_MIME,
                    320, 180));
            fail("an unregistered codec must not be switched to");
        } catch (CodecSwitchException expected) {
            assertTrue(expected.getCause() instanceof CodecNotFoundException);
        }

        assertEquals(1, runtime.getConfigEpoch());
        assertEquals(first.getConfigHash(), runtime.getDescriptor().getConfigHash());
        assertSame(oldEncoder, fake.getLastEncoder());
        assertFalse(oldEncoder.isStopped());
        assertFalse(oldEncoder.isClosed());
        assertTrue(listener.descriptors.isEmpty());
        assertFalse(runtime.isClosed());

        // Nothing was reserved: the same epoch can be retried once the codec is registered.
        FakeCodec late = new FakeCodec("late-codec", 0, false, false);
        registry.register(late.registration());
        runtime.switchDescriptor(descriptor(2, "late-codec", FakeCodec.DEFAULT_MIME, 320, 180));

        assertEquals(2, runtime.getConfigEpoch());
        assertEquals(2, runtime.getDescriptor().getConfigEpoch());
        assertTrue(late.getLastEncoder().isStarted());
        assertTrue(oldEncoder.isStopped());
        assertEquals(1, listener.descriptors.size());
    }

    @Test
    public void updateParameters_callsTheEncoderOutsideTheStateLock() {
        FakeCodec fake = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        CodecRegistry registry = new CodecRegistry();
        registry.register(fake.registration());
        StreamRuntime runtime = newRuntime(registry,
                descriptor(1, FakeCodec.DEFAULT_CODEC_ID, FakeCodec.DEFAULT_MIME, 640, 360));
        runtime.start();

        AtomicBoolean lockFree = new AtomicBoolean(false);
        fake.setOnUpdateParameters(() -> lockFree.set(canReadStateFromAnotherThread(runtime)));

        runtime.updateParameters(new StreamParameters(1_500_000, 15, 500));

        assertTrue("updateParameters must not hold the state lock across the encoder call",
                lockFree.get());
        assertEquals(1_500_000, runtime.getParameters().getTargetBitrateBps());
        assertEquals(15, runtime.getParameters().getFrameRateHint());
    }

    @Test
    public void updateParameters_encoderFailure_keepsCachedParameters() {
        FakeCodec fake = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        CodecRegistry registry = new CodecRegistry();
        registry.register(fake.registration());
        StreamRuntime runtime = newRuntime(registry,
                descriptor(1, FakeCodec.DEFAULT_CODEC_ID, FakeCodec.DEFAULT_MIME, 640, 360));
        runtime.start();
        StreamParameters before = runtime.getParameters();
        StreamParameters rejected = new StreamParameters(1_500_000, 15, 500);
        fake.failNextUpdateParameters();

        try {
            runtime.updateParameters(rejected);
            fail("an encoder failure must propagate");
        } catch (IllegalStateException expected) {
            assertNotNull(expected);
        }

        assertSame(before, runtime.getParameters());
        assertEquals(before.getTargetBitrateBps(),
                fake.getLastEncoder().getLastParameters().getTargetBitrateBps());
        assertEquals(RuntimeState.READY, runtime.getState());
    }

    @Test
    public void updateParameters_afterConcurrentSwitch_reappliesToTheNewEncoder() {
        FakeCodec fake = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        CodecRegistry registry = new CodecRegistry();
        registry.register(fake.registration());
        StreamRuntime runtime = newRuntime(registry,
                descriptor(1, FakeCodec.DEFAULT_CODEC_ID, FakeCodec.DEFAULT_MIME, 640, 360));
        runtime.start();
        FakeCodec.FakeEncoder oldEncoder = fake.getLastEncoder();
        StreamParameters updated = new StreamParameters(2_000_000, 15, 500);

        // The switch lands while the old encoder is inside updateParameters, so the runtime has to
        // notice the generation change and hand the same hints to the new encoder.
        fake.setOnUpdateParameters(() -> runtime.switchDescriptor(
                descriptor(2, FakeCodec.DEFAULT_CODEC_ID, FakeCodec.DEFAULT_MIME, 320, 180)));

        runtime.updateParameters(updated);

        FakeCodec.FakeEncoder newEncoder = fake.getLastEncoder();
        assertNotSame(oldEncoder, newEncoder);
        assertEquals(2_000_000, newEncoder.getLastParameters().getTargetBitrateBps());
        assertEquals(15, newEncoder.getLastParameters().getFrameRateHint());
        assertEquals(2_000_000, runtime.getParameters().getTargetBitrateBps());
        assertEquals(2, runtime.getConfigEpoch());
    }

    /**
     * Returns whether another thread can read the runtime state while the caller is inside an
     * encoder lifecycle call; a bounded join turns the answer into a boolean instead of a hang.
     */
    private static boolean canReadStateFromAnotherThread(StreamRuntime runtime) {
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

    private static StreamRuntime newRuntime(CodecRegistry registry, StreamDescriptor descriptor) {
        return new StreamRuntime(registry, STREAM_KEY, descriptor, StreamParameters.DEFAULT,
                new MutableClock(), JOIN_INTERVAL_MS);
    }

    private static StreamDescriptor descriptor(int configEpoch, String codecId, String mime,
                                               int width, int height) {
        return StreamDescriptor.builder(STREAM_KEY, configEpoch, codecId, mime,
                BitstreamFormat.ANNEX_B)
                .codedSize(width, height)
                .visibleSize(width, height)
                .build();
    }

    private static final class RecordingListener implements StreamRuntimeListener {

        private final List<EncodedAccessUnit> accessUnits = new ArrayList<>();

        private final List<StreamDescriptor> descriptors = new ArrayList<>();

        private final List<EncoderException> failures = new ArrayList<>();

        @Override
        public void onAccessUnit(EncodedAccessUnit accessUnit) {
            accessUnits.add(accessUnit);
        }

        @Override
        public void onDescriptorChanged(StreamDescriptor descriptor) {
            descriptors.add(descriptor);
        }

        @Override
        public void onEncoderFailed(StreamKey streamKey, EncoderException failure) {
            failures.add(failure);
        }
    }
}
