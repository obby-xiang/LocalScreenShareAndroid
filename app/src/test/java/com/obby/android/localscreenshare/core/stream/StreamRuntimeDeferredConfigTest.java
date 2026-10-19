package com.obby.android.localscreenshare.core.stream;

import android.view.Surface;

import com.obby.android.localscreenshare.core.codec.CodecRegistration;
import com.obby.android.localscreenshare.core.codec.CodecRegistry;
import com.obby.android.localscreenshare.core.codec.CodecSwitchException;
import com.obby.android.localscreenshare.core.codec.Encoder;
import com.obby.android.localscreenshare.core.codec.EncoderConfig;
import com.obby.android.localscreenshare.core.codec.EncoderException;
import com.obby.android.localscreenshare.core.codec.EncoderFactory;
import com.obby.android.localscreenshare.core.codec.EncoderOutput;
import com.obby.android.localscreenshare.core.model.BitstreamFormat;
import com.obby.android.localscreenshare.core.model.CodecConfigPart;
import com.obby.android.localscreenshare.core.model.ColorInfo;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.model.StreamParameters;
import com.obby.android.localscreenshare.core.support.FakeCodec;
import com.obby.android.localscreenshare.core.support.FakeDeferredCodec;
import com.obby.android.localscreenshare.core.support.MutableClock;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class StreamRuntimeDeferredConfigTest {

    private static final StreamKey STREAM_KEY = new StreamKey("session-1", "stream-1");

    private static final long JOIN_INTERVAL_MS = 100L;

    @Test
    public void start_setsStateAndGenerationBeforeEncoderStart() {
        FakeDeferredCodec codec = new FakeDeferredCodec();
        StreamRuntime runtime = newRuntime(codec, deferredDescriptor(1, 1920, 1080));
        final AtomicBoolean probeRan = new AtomicBoolean();
        final AtomicReference<RuntimeState> stateAtStart = new AtomicReference<>();
        final AtomicReference<StreamDescriptor> descriptorAtStart = new AtomicReference<>();
        final AtomicReference<StreamKey> streamKeyAtStart = new AtomicReference<>();
        final AtomicInteger epochAtStart = new AtomicInteger();
        codec.setStartProbe(() -> {
            probeRan.set(true);
            stateAtStart.set(runtime.getState());
            descriptorAtStart.set(runtime.getDescriptor());
            streamKeyAtStart.set(runtime.getStreamKey());
            epochAtStart.set(runtime.getConfigEpoch());
        });

        runtime.start();

        assertTrue("encoder.start must run the probe", probeRan.get());
        assertEquals(RuntimeState.CONFIGURING, stateAtStart.get());
        assertNull(descriptorAtStart.get());
        assertEquals(STREAM_KEY, streamKeyAtStart.get());
        assertEquals(1, epochAtStart.get());
        assertEquals(RuntimeState.CONFIGURING, runtime.getState());
    }

    @Test
    public void start_deferredKeepsDescriptorNullUntilConfig() {
        FakeDeferredCodec codec = new FakeDeferredCodec();
        StreamRuntime runtime = newRuntime(codec, deferredDescriptor(1, 1920, 1080));
        RecordingListener listener = new RecordingListener();
        runtime.addListener(listener);

        runtime.start();

        FakeDeferredCodec.FakeEncoder encoder = codec.getLastEncoder();
        assertNotNull(encoder);
        assertEquals(1, encoder.getStartCount());
        assertTrue(encoder.isStarted());
        assertFalse(encoder.isClosed());
        assertEquals(RuntimeState.CONFIGURING, runtime.getState());
        assertNull(runtime.getDescriptor());
        assertNull(runtime.getInputSurface());
        assertEquals(1, runtime.getConfigEpoch());
        assertEquals(STREAM_KEY, runtime.getStreamKey());
        assertFalse("only READY may forward join-point requests", runtime.requestJoinPoint());
        assertTrue(listener.descriptors.isEmpty());
        assertTrue(listener.accessUnits.isEmpty());
    }

    @Test
    public void start_nonDeferredPublishesDescriptorAndIsReady() {
        FakeCodec fake = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        StreamDescriptor requested = fakeDescriptor(1, 64, 48);
        StreamRuntime runtime = newRuntime(fake, requested);
        RecordingListener listener = new RecordingListener();
        runtime.addListener(listener);

        runtime.start();

        assertEquals(RuntimeState.READY, runtime.getState());
        assertNotNull(runtime.getDescriptor());
        assertEquals(1, runtime.getConfigEpoch());
        assertEquals(requested.getConfigHash(), runtime.getDescriptor().getConfigHash());
        assertEquals(STREAM_KEY, runtime.getStreamKey());
        assertEquals(requested.getStreamKey(), runtime.getDescriptor().getStreamKey());
        assertTrue(fake.getLastEncoder().isStarted());
        assertNull(runtime.getInputSurface());
        assertTrue(listener.failures.isEmpty());
    }

    @Test
    public void start_nonDeferredSurfaceValidationOnlyUsesSurfaceInputRequired() {
        FakeCodec fake = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, true, false);
        assertFalse("the non-deferred fake carries no config parts",
                fake.registration().getDescriptor().hasConfigParts());
        StreamRuntime runtime = newRuntime(fake, fakeDescriptor(1, 64, 48));
        RecordingListener listener = new RecordingListener();
        runtime.addListener(listener);

        try {
            runtime.start();
            fail("surface-required non-deferred codec with a null surface must fail start");
        } catch (EncoderException expected) {
            assertNotNull(expected);
        }

        assertEquals(RuntimeState.FAILED, runtime.getState());
        assertNull(runtime.getDescriptor());
        assertNull(runtime.getInputSurface());
        FakeCodec.FakeEncoder encoder = fake.getLastEncoder();
        assertNotNull(encoder);
        assertFalse(encoder.isStarted());
        assertTrue(encoder.isClosed());
        assertEquals(1, listener.failures.size());
        assertEquals(STREAM_KEY, listener.failedKeys.get(0));
    }

    @Test
    public void factoryFailure_setsFailedNotifiesAndDoesNotPublish() {
        FakeDeferredCodec codec = new FakeDeferredCodec();
        StreamRuntime runtime = newRuntime(codec, deferredDescriptor(1, 1920, 1080));
        RecordingListener listener = new RecordingListener();
        runtime.addListener(listener);
        codec.failNextEncoderCreate();

        try {
            runtime.start();
            fail("encoder factory failure must abort start");
        } catch (EncoderException expected) {
            assertNotNull(expected);
        }

        assertEquals(RuntimeState.FAILED, runtime.getState());
        assertNull(runtime.getDescriptor());
        assertNull(codec.getLastEncoder());
        assertEquals(0, codec.getEncoderCreateCount());
        assertEquals(1, listener.failures.size());
        assertNotNull(listener.failures.get(0));
        assertEquals(STREAM_KEY, listener.failedKeys.get(0));

        runtime.start();

        assertEquals(RuntimeState.CONFIGURING, runtime.getState());
        assertNotNull(codec.getLastEncoder());
        assertEquals(1, codec.getEncoderCreateCount());
    }

    @Test
    public void surfaceValidationFailure_setsFailedClosesEncoderAndAllowsRetry() {
        FakeDeferredCodec codec = new FakeDeferredCodec(FakeDeferredCodec.DEFAULT_CODEC_ID, true);
        StreamRuntime runtime = newRuntime(codec, deferredDescriptor(1, 1920, 1080));
        RecordingListener listener = new RecordingListener();
        runtime.addListener(listener);

        try {
            runtime.start();
            fail("surface validation failure must abort start");
        } catch (EncoderException expected) {
            assertNotNull(expected);
        }

        FakeDeferredCodec.FakeEncoder rejected = codec.getLastEncoder();
        assertNotNull(rejected);
        assertEquals("surface validation must run before encoder.start", 0,
                rejected.getStartCount());
        assertFalse(rejected.isStarted());
        assertEquals(1, rejected.getCloseCount());
        assertEquals(RuntimeState.FAILED, runtime.getState());
        assertNull(runtime.getDescriptor());
        assertNull(runtime.getInputSurface());
        assertEquals(1, listener.failures.size());

        // A non-null Surface cannot be created on the JVM, so the retry is allowed
        // to run again but is rejected by the same surface validation.
        try {
            runtime.start();
            fail("retry without a surface must fail surface validation again");
        } catch (EncoderException expected) {
            assertNotNull(expected);
        }

        assertEquals(2, codec.getEncoderCreateCount());
        FakeDeferredCodec.FakeEncoder retried = codec.getLastEncoder();
        assertNotSame(rejected, retried);
        assertEquals(1, retried.getCloseCount());
        assertEquals(RuntimeState.FAILED, runtime.getState());
        assertNull(runtime.getDescriptor());
        assertEquals(2, listener.failures.size());
    }

    @Test
    public void encoderStartFailure_setsFailedClosesEncoderAndAllowsRetry() {
        FakeDeferredCodec codec = new FakeDeferredCodec();
        StreamRuntime runtime = newRuntime(codec, deferredDescriptor(1, 1920, 1080));
        RecordingListener listener = new RecordingListener();
        runtime.addListener(listener);
        codec.failNextEncoderStart();

        try {
            runtime.start();
            fail("encoder start failure must abort start");
        } catch (EncoderException expected) {
            assertNotNull(expected);
        }

        FakeDeferredCodec.FakeEncoder failed = codec.getLastEncoder();
        assertNotNull(failed);
        assertEquals(1, failed.getStartCount());
        assertFalse(failed.isStarted());
        assertEquals(1, failed.getCloseCount());
        assertEquals(RuntimeState.FAILED, runtime.getState());
        assertNull(runtime.getDescriptor());
        assertNull(runtime.getInputSurface());
        assertEquals(1, listener.failures.size());
        assertEquals(STREAM_KEY, listener.failedKeys.get(0));

        runtime.start();

        FakeDeferredCodec.FakeEncoder active = codec.getLastEncoder();
        assertNotSame(failed, active);
        assertEquals(2, codec.getEncoderCreateCount());
        assertEquals(1, active.getStartCount());
        assertEquals(0, active.getCloseCount());
        assertEquals(RuntimeState.CONFIGURING, runtime.getState());
        assertNull(runtime.getDescriptor());

        active.emitConfig(encoderConfig(1280, 720, new ColorInfo("bt709", "sdr", true),
                configParts((byte) 0x01)));

        assertEquals(RuntimeState.READY, runtime.getState());
        assertNotNull(runtime.getDescriptor());
        assertEquals(1280, runtime.getDescriptor().getCodedWidth());
    }

    @Test
    public void encoderConfig_publishesFinalDescriptorWithSameEpochAndConfigParts() {
        FakeDeferredCodec codec = new FakeDeferredCodec();
        StreamDescriptor requested = deferredDescriptor(1, 1920, 1080);
        StreamRuntime runtime = newRuntime(codec, requested);
        RecordingListener listener = new RecordingListener();
        runtime.addListener(listener);
        runtime.start();
        FakeDeferredCodec.FakeEncoder encoder = codec.getLastEncoder();

        encoder.emitConfig(encoderConfig(1280, 720, new ColorInfo("bt709", "sdr", true),
                configParts((byte) 0x01)));

        StreamDescriptor published = runtime.getDescriptor();
        assertNotNull(published);
        assertEquals(RuntimeState.READY, runtime.getState());
        assertEquals(1, runtime.getConfigEpoch());
        assertEquals(requested.getConfigEpoch(), published.getConfigEpoch());
        assertEquals(1280, published.getCodedWidth());
        assertEquals(720, published.getCodedHeight());
        assertEquals(requested.getProfile(), published.getProfile());
        assertEquals(requested.getLevel(), published.getLevel());
        assertEquals(2, published.getConfigParts().size());
        assertEquals("csd-0", published.getConfigParts().get(0).getId());
        assertArrayEquals(new byte[]{(byte) 0x67, (byte) 0x01},
                published.getConfigParts().get(0).getPayload());
        assertEquals("csd-1", published.getConfigParts().get(1).getId());
        assertArrayEquals(new byte[]{(byte) 0x68, (byte) 0x01},
                published.getConfigParts().get(1).getPayload());
        assertEquals("bt709", published.getColorInfo().getColorSpace());
        assertEquals("sdr", published.getColorInfo().getTransferFunction());
        assertTrue(published.getColorInfo().isFullRange());
        assertEquals(STREAM_KEY, published.getStreamKey());
        assertEquals(FakeDeferredCodec.DEFAULT_CODEC_ID, published.getCodecId());
        assertEquals(FakeDeferredCodec.DEFAULT_MIME, published.getMime());
        assertEquals(BitstreamFormat.ANNEX_B, published.getBitstreamFormat());
        assertNotEquals(requested.getConfigHash(), published.getConfigHash());
        assertEquals(1, listener.descriptors.size());
        assertEquals(published.getConfigHash(), listener.descriptors.get(0).getConfigHash());
    }

    @Test
    public void encoderConfig_notifiesDescriptorBeforeFirstAccessUnit() {
        FakeDeferredCodec codec = new FakeDeferredCodec();
        final StreamRuntime runtime = newRuntime(codec, deferredDescriptor(1, 1920, 1080));
        final List<String> events = new ArrayList<>();
        final AtomicReference<RuntimeState> stateAtNotification = new AtomicReference<>();
        final AtomicBoolean publishedAtNotification = new AtomicBoolean();
        runtime.addListener(new StreamRuntimeListener() {
            @Override
            public void onAccessUnit(EncodedAccessUnit accessUnit) {
                events.add("au:" + accessUnit.getConfigEpoch() + ":" + accessUnit.getSequence());
            }

            @Override
            public void onDescriptorChanged(StreamDescriptor descriptor) {
                events.add("descriptor:" + descriptor.getConfigEpoch());
                stateAtNotification.set(runtime.getState());
                publishedAtNotification.set(runtime.getDescriptor() != null);
            }

            @Override
            public void onEncoderFailed(StreamKey streamKey, EncoderException failure) {
                events.add("failed");
            }
        });
        runtime.start();
        FakeDeferredCodec.FakeEncoder encoder = codec.getLastEncoder();

        encoder.emitConfig(encoderConfig(1280, 720, ColorInfo.UNSPECIFIED,
                configParts((byte) 0x02)));
        encoder.emit(false, 1, new byte[]{0x31});

        assertEquals(Arrays.asList("descriptor:1", "au:1:0"), events);
        assertEquals(RuntimeState.READY, stateAtNotification.get());
        assertTrue(publishedAtNotification.get());
    }

    @Test
    public void accessUnitBeforeConfig_setsFailedNotifiesAndDoesNotCloseInCallback() {
        FakeDeferredCodec codec = new FakeDeferredCodec();
        StreamRuntime runtime = newRuntime(codec, deferredDescriptor(1, 1920, 1080));
        RecordingListener listener = new RecordingListener();
        runtime.addListener(listener);
        runtime.start();
        FakeDeferredCodec.FakeEncoder encoder = codec.getLastEncoder();

        encoder.emit(false, 1, new byte[]{0x41});

        assertEquals(RuntimeState.FAILED, runtime.getState());
        assertNull(runtime.getDescriptor());
        assertEquals(1, listener.failures.size());
        assertNotNull(listener.failures.get(0));
        assertEquals(STREAM_KEY, listener.failedKeys.get(0));
        assertTrue(listener.accessUnits.isEmpty());
        assertTrue(listener.descriptors.isEmpty());
        assertEquals("the callback must not close the encoder", 0, encoder.getCloseCount());
        assertEquals("the callback must not stop the encoder", 0, encoder.getStopCount());
        assertEquals(1, encoder.getStartCount());
        assertEquals(1, codec.getEncoderCreateCount());

        encoder.emitConfig(encoderConfig(1280, 720, ColorInfo.UNSPECIFIED,
                configParts((byte) 0x03)));

        assertEquals(RuntimeState.FAILED, runtime.getState());
        assertNull(runtime.getDescriptor());
        assertTrue(listener.descriptors.isEmpty());
        assertEquals(1, listener.failures.size());
    }

    @Test
    public void nonDeferredAccessUnitBeforePublished_isDroppedWithoutStateChange() {
        FakeCodec fake = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        CodecRegistry registry = new CodecRegistry();
        registry.register(prePublishEmittingRegistration(fake, new byte[]{0x51}));
        StreamRuntime runtime = newRuntime(registry, fakeDescriptor(1, 64, 48));
        RecordingListener listener = new RecordingListener();
        runtime.addListener(listener);

        runtime.start();

        assertTrue("access units emitted before publication must be dropped",
                listener.accessUnits.isEmpty());
        assertEquals(RuntimeState.READY, runtime.getState());
        assertNotNull(runtime.getDescriptor());
        assertEquals("non-deferred start publishes internally without a descriptor notification",
                0, listener.descriptors.size());
        assertTrue(listener.failures.isEmpty());

        fake.emitNonJoin(new byte[]{0x52});

        assertEquals(1, listener.accessUnits.size());
        assertArrayEquals(new byte[]{0x52}, listener.accessUnits.get(0).getPayload());
    }

    @Test
    public void lateConfigAndAuFromFailedAttempt_areIgnored() {
        FakeDeferredCodec codec = new FakeDeferredCodec();
        StreamRuntime runtime = newRuntime(codec, deferredDescriptor(1, 1920, 1080));
        RecordingListener listener = new RecordingListener();
        runtime.addListener(listener);
        codec.failNextEncoderStart();
        try {
            runtime.start();
            fail("encoder start failure must abort start");
        } catch (EncoderException expected) {
            assertNotNull(expected);
        }
        FakeDeferredCodec.FakeEncoder failedAttempt = codec.getLastEncoder();
        assertNotNull(failedAttempt);

        runtime.start();
        FakeDeferredCodec.FakeEncoder active = codec.getLastEncoder();
        assertNotSame(failedAttempt, active);

        failedAttempt.emitConfig(encoderConfig(640, 360, ColorInfo.UNSPECIFIED,
                configParts((byte) 0x04)));
        failedAttempt.emit(false, 1, new byte[]{0x61});
        failedAttempt.emit(true, 1, new byte[]{0x62});

        assertEquals(RuntimeState.CONFIGURING, runtime.getState());
        assertNull(runtime.getDescriptor());
        assertTrue(listener.descriptors.isEmpty());
        assertTrue(listener.accessUnits.isEmpty());
        assertEquals(1, listener.failures.size());

        active.emitConfig(encoderConfig(1280, 720, ColorInfo.UNSPECIFIED,
                configParts((byte) 0x05)));

        StreamDescriptor published = runtime.getDescriptor();
        assertNotNull(published);
        assertEquals(RuntimeState.READY, runtime.getState());
        assertEquals(1, published.getConfigEpoch());
        assertEquals(1280, published.getCodedWidth());
        assertEquals(1, listener.descriptors.size());

        failedAttempt.emitConfig(encoderConfig(320, 180, ColorInfo.UNSPECIFIED,
                configParts((byte) 0x06)));
        failedAttempt.emit(false, 1, new byte[]{0x63});

        assertEquals(1280, runtime.getDescriptor().getCodedWidth());
        assertEquals(1, listener.descriptors.size());
        assertTrue(listener.accessUnits.isEmpty());
    }

    @Test
    public void retryAfterFailure_closesOldEncoderOutsideLockAndUsesNewAttempt() {
        FakeDeferredCodec codec = new FakeDeferredCodec();
        final StreamRuntime runtime = newRuntime(codec, deferredDescriptor(1, 1920, 1080));
        runtime.start();
        final FakeDeferredCodec.FakeEncoder failedAttempt = codec.getLastEncoder();
        failedAttempt.fail(new EncoderException("injected deferred failure"));
        assertEquals(RuntimeState.FAILED, runtime.getState());
        assertNull(runtime.getDescriptor());

        final CountDownLatch stateObserved = new CountDownLatch(1);
        final AtomicBoolean closeProbeRan = new AtomicBoolean();
        final AtomicBoolean runtimeLockFree = new AtomicBoolean();
        failedAttempt.setCloseProbe(() -> {
            closeProbeRan.set(true);
            Thread observer = new Thread(() -> {
                runtime.getState();
                stateObserved.countDown();
            }, "runtime-lock-probe");
            observer.setDaemon(true);
            observer.start();
            try {
                runtimeLockFree.set(stateObserved.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                runtimeLockFree.set(false);
            }
        });

        runtime.start();

        FakeDeferredCodec.FakeEncoder active = codec.getLastEncoder();
        assertNotSame(failedAttempt, active);
        assertEquals(2, codec.getEncoderCreateCount());
        assertTrue("the retry must close the failed encoder", closeProbeRan.get());
        assertEquals(1, failedAttempt.getCloseCount());
        assertTrue("the failed encoder must be closed outside the runtime lock",
                runtimeLockFree.get());
        assertEquals(RuntimeState.CONFIGURING, runtime.getState());
        assertNull(runtime.getDescriptor());

        active.emitConfig(encoderConfig(1280, 720, ColorInfo.UNSPECIFIED,
                configParts((byte) 0x07)));

        assertEquals(RuntimeState.READY, runtime.getState());
        assertNotNull(runtime.getDescriptor());
    }

    @Test
    public void onEncoderFailure_ignoresStalePendingAttempt() {
        FakeDeferredCodec codec = new FakeDeferredCodec();
        StreamRuntime runtime = newRuntime(codec, deferredDescriptor(1, 1920, 1080));
        RecordingListener listener = new RecordingListener();
        runtime.addListener(listener);
        runtime.start();
        FakeDeferredCodec.FakeEncoder attempt1 = codec.getLastEncoder();

        attempt1.fail(new EncoderException("first failure"));

        assertEquals(RuntimeState.FAILED, runtime.getState());
        assertNull(runtime.getDescriptor());
        assertEquals(1, listener.failures.size());

        runtime.start();
        FakeDeferredCodec.FakeEncoder attempt2 = codec.getLastEncoder();
        assertNotSame(attempt1, attempt2);
        assertEquals(RuntimeState.CONFIGURING, runtime.getState());

        attempt1.fail(new EncoderException("stale failure"));

        assertEquals(RuntimeState.CONFIGURING, runtime.getState());
        assertNull(runtime.getDescriptor());
        assertEquals("stale attempts must not notify", 1, listener.failures.size());

        attempt2.fail(new EncoderException("active failure"));

        assertEquals(RuntimeState.FAILED, runtime.getState());
        assertNull(runtime.getDescriptor());
        assertEquals(2, listener.failures.size());
        assertEquals(STREAM_KEY, listener.failedKeys.get(1));

        attempt2.fail(new EncoderException("already failed"));

        assertEquals(2, listener.failures.size());

        runtime.start();
        FakeDeferredCodec.FakeEncoder attempt3 = codec.getLastEncoder();
        attempt3.emitConfig(encoderConfig(1280, 720, ColorInfo.UNSPECIFIED,
                configParts((byte) 0x08)));
        assertEquals(RuntimeState.READY, runtime.getState());

        attempt2.fail(new EncoderException("late failure"));

        assertEquals(RuntimeState.READY, runtime.getState());
        assertNotNull(runtime.getDescriptor());
        assertEquals(2, listener.failures.size());
    }

    @Test
    public void duplicateConfig_isIgnoredAndDoesNotRepublish() {
        FakeDeferredCodec codec = new FakeDeferredCodec();
        StreamRuntime runtime = newRuntime(codec, deferredDescriptor(1, 1920, 1080));
        RecordingListener listener = new RecordingListener();
        runtime.addListener(listener);
        runtime.start();
        FakeDeferredCodec.FakeEncoder encoder = codec.getLastEncoder();

        encoder.emitConfig(encoderConfig(1280, 720, ColorInfo.UNSPECIFIED,
                configParts((byte) 0x09)));
        StreamDescriptor first = runtime.getDescriptor();
        assertNotNull(first);

        encoder.emitConfig(encoderConfig(640, 360, new ColorInfo("bt2020", "hlg", true),
                configParts((byte) 0x0A)));

        assertEquals(RuntimeState.READY, runtime.getState());
        assertEquals(first.getConfigHash(), runtime.getDescriptor().getConfigHash());
        assertEquals(1280, runtime.getDescriptor().getCodedWidth());
        assertEquals(720, runtime.getDescriptor().getCodedHeight());
        assertEquals(2, runtime.getDescriptor().getConfigParts().size());
        assertArrayEquals(new byte[]{(byte) 0x67, (byte) 0x09},
                runtime.getDescriptor().getConfigParts().get(0).getPayload());
        assertEquals(1, listener.descriptors.size());

        encoder.emit(false, 1, new byte[]{0x71});

        assertEquals(1, listener.accessUnits.size());
        assertArrayEquals(new byte[]{0x71}, listener.accessUnits.get(0).getPayload());
    }

    @Test
    public void getDescriptor_isNullBeforePublishAndGetStreamKeyAlwaysAvailable() {
        FakeDeferredCodec codec = new FakeDeferredCodec();
        StreamRuntime runtime = newRuntime(codec, deferredDescriptor(1, 1920, 1080));

        assertEquals(RuntimeState.IDLE, runtime.getState());
        assertNull(runtime.getDescriptor());
        assertEquals(STREAM_KEY, runtime.getStreamKey());
        assertEquals(1, runtime.getConfigEpoch());
        assertFalse(runtime.requestJoinPoint());
        assertFalse(runtime.isClosed());

        runtime.start();

        assertEquals(RuntimeState.CONFIGURING, runtime.getState());
        assertNull(runtime.getDescriptor());
        assertEquals(STREAM_KEY, runtime.getStreamKey());

        runtime.close();

        assertEquals(RuntimeState.CLOSED, runtime.getState());
        assertTrue(runtime.isClosed());
        assertEquals(STREAM_KEY, runtime.getStreamKey());
        assertNull(runtime.getDescriptor());
        assertNull(runtime.getInputSurface());
        assertFalse(runtime.requestJoinPoint());
    }

    @Test
    public void closeDuringConfiguring_closesEncoderOnceAndRejectsLateCallbacks() {
        FakeDeferredCodec codec = new FakeDeferredCodec();
        StreamRuntime runtime = newRuntime(codec, deferredDescriptor(1, 1920, 1080));
        RecordingListener listener = new RecordingListener();
        runtime.addListener(listener);
        runtime.start();
        FakeDeferredCodec.FakeEncoder encoder = codec.getLastEncoder();

        runtime.close();

        assertEquals(RuntimeState.CLOSED, runtime.getState());
        assertTrue(runtime.isClosed());
        assertNull(runtime.getDescriptor());
        assertEquals(1, encoder.getCloseCount());

        runtime.close();

        assertEquals(1, encoder.getCloseCount());

        encoder.emitConfig(encoderConfig(1280, 720, ColorInfo.UNSPECIFIED,
                configParts((byte) 0x0B)));
        encoder.emit(false, 1, new byte[]{(byte) 0x81});
        encoder.fail(new EncoderException("late failure"));

        assertEquals(RuntimeState.CLOSED, runtime.getState());
        assertNull(runtime.getDescriptor());
        assertNull(runtime.getInputSurface());
        assertTrue(listener.descriptors.isEmpty());
        assertTrue(listener.accessUnits.isEmpty());
        assertTrue(listener.failures.isEmpty());

        try {
            runtime.updateParameters(new StreamParameters(1_000_000, 15, 500));
            fail("updateParameters on a closed runtime must be rejected");
        } catch (IllegalStateException expected) {
            assertNotNull(expected);
        }
    }

    @Test
    public void switchDescriptorOnDeferredCodec_switchesAndRepublishesOnConfig() {
        FakeDeferredCodec codec = new FakeDeferredCodec();
        StreamRuntime runtime = newRuntime(codec, deferredDescriptor(1, 1920, 1080));
        RecordingListener listener = new RecordingListener();
        runtime.addListener(listener);
        runtime.start();
        FakeDeferredCodec.FakeEncoder oldEncoder = codec.getLastEncoder();
        oldEncoder.emitConfig(encoderConfig(1920, 1080, ColorInfo.UNSPECIFIED,
                configParts((byte) 0x0C)));
        assertNotNull(runtime.getDescriptor());

        runtime.switchDescriptor(deferredDescriptor(2, 1280, 720));

        FakeDeferredCodec.FakeEncoder newEncoder = codec.getLastEncoder();
        assertNotSame(oldEncoder, newEncoder);
        assertTrue(oldEncoder.isStopped());
        assertTrue(newEncoder.isStarted());
        assertEquals(RuntimeState.CONFIGURING, runtime.getState());
        assertEquals(2, runtime.getConfigEpoch());
        assertNull(runtime.getDescriptor());

        newEncoder.emitConfig(encoderConfig(1280, 720, ColorInfo.UNSPECIFIED,
                configParts((byte) 0x0D)));

        assertEquals(RuntimeState.READY, runtime.getState());
        assertNotNull(runtime.getDescriptor());
        assertEquals(2, runtime.getDescriptor().getConfigEpoch());
        assertEquals(1280, runtime.getDescriptor().getCodedWidth());
    }

    @Test
    public void switchDescriptor_nonDeferredToDeferredNext_switches() {
        FakeCodec fake = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        FakeDeferredCodec deferred = new FakeDeferredCodec();
        CodecRegistry registry = new CodecRegistry();
        registry.register(fake.registration());
        registry.register(deferred.registration());
        StreamRuntime runtime = newRuntime(registry, fakeDescriptor(1, 64, 48));
        runtime.addListener(new RecordingListener());
        runtime.start();
        FakeCodec.FakeEncoder oldEncoder = fake.getLastEncoder();

        runtime.switchDescriptor(deferredDescriptor(2, 32, 24));

        assertEquals(1, deferred.getEncoderCreateCount());
        assertNotNull(deferred.getLastEncoder());
        assertTrue(oldEncoder.isStopped());
        assertEquals(2, runtime.getConfigEpoch());
        assertEquals(RuntimeState.CONFIGURING, runtime.getState());
    }

    @Test
    public void deferredSwitch_closesOldEncoderAfterNewJoinPoint() {
        FakeDeferredCodec codec = new FakeDeferredCodec();
        StreamRuntime runtime = newRuntime(codec, deferredDescriptor(1, 1920, 1080));
        runtime.addListener(new RecordingListener());
        runtime.start();
        FakeDeferredCodec.FakeEncoder oldEncoder = codec.getLastEncoder();
        oldEncoder.emitConfig(encoderConfig(1920, 1080, ColorInfo.UNSPECIFIED,
                configParts((byte) 0x0C)));

        runtime.switchDescriptor(deferredDescriptor(2, 1280, 720));
        FakeDeferredCodec.FakeEncoder newEncoder = codec.getLastEncoder();
        newEncoder.emitConfig(encoderConfig(1280, 720, ColorInfo.UNSPECIFIED,
                configParts((byte) 0x0D)));

        // The old encoder is stopped on the switch but released only after the new join point.
        assertTrue(oldEncoder.isStopped());
        assertFalse(oldEncoder.isClosed());

        newEncoder.emit(true, 2, new byte[]{0x01});

        assertTrue(oldEncoder.isClosed());
    }

    @Test
    public void nonDeferredSwitch_updatesDescriptorEpochAndReady() {
        FakeCodec fake = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        StreamRuntime runtime = newRuntime(fake, fakeDescriptor(1, 64, 48));
        RecordingListener listener = new RecordingListener();
        runtime.addListener(listener);
        runtime.start();
        FakeCodec.FakeEncoder oldEncoder = fake.getLastEncoder();
        StreamDescriptor next = fakeDescriptor(2, 32, 24);

        runtime.switchDescriptor(next);

        FakeCodec.FakeEncoder newEncoder = fake.getLastEncoder();
        assertNotSame(oldEncoder, newEncoder);
        assertTrue(oldEncoder.isStopped());
        assertTrue(newEncoder.isStarted());
        assertEquals(RuntimeState.READY, runtime.getState());
        assertEquals(2, runtime.getConfigEpoch());
        assertEquals(next.getConfigHash(), runtime.getDescriptor().getConfigHash());
        assertEquals(1, listener.descriptors.size());
        assertEquals(2, listener.descriptors.get(0).getConfigEpoch());
        assertTrue(listener.failures.isEmpty());
    }

    @Test
    public void nonDeferredSwitchFailure_keepsOldDescriptorEpochAndState() {
        FakeCodec fake = new FakeCodec(FakeCodec.DEFAULT_CODEC_ID, 0, false, false);
        StreamRuntime runtime = newRuntime(fake, fakeDescriptor(1, 64, 48));
        RecordingListener listener = new RecordingListener();
        runtime.addListener(listener);
        runtime.start();
        FakeCodec.FakeEncoder oldEncoder = fake.getLastEncoder();
        StreamDescriptor oldDescriptor = runtime.getDescriptor();
        assertNotNull(oldDescriptor);
        fake.failNextEncoderStart();

        try {
            runtime.switchDescriptor(fakeDescriptor(2, 32, 24));
            fail("encoder start failure must abort the switch");
        } catch (CodecSwitchException expected) {
            assertNotNull(expected.getCause());
        }

        FakeCodec.FakeEncoder rejected = fake.getLastEncoder();
        assertNotSame(oldEncoder, rejected);
        assertTrue(rejected.isClosed());
        assertFalse(rejected.isStarted());
        assertFalse(oldEncoder.isStopped());
        assertFalse(oldEncoder.isClosed());
        assertEquals(1, runtime.getConfigEpoch());
        assertEquals(RuntimeState.READY, runtime.getState());
        assertEquals(oldDescriptor.getConfigHash(), runtime.getDescriptor().getConfigHash());
        assertEquals(0, listener.descriptors.size());
    }

    private static EncoderConfig encoderConfig(int width, int height, ColorInfo colorInfo,
                                               List<CodecConfigPart> configParts) {
        return new EncoderConfig(width, height, colorInfo, configParts);
    }

    private static List<CodecConfigPart> configParts(byte seed) {
        return Arrays.asList(
                new CodecConfigPart("csd-0", new byte[]{(byte) 0x67, seed}),
                new CodecConfigPart("csd-1", new byte[]{(byte) 0x68, seed}));
    }

    private static StreamDescriptor deferredDescriptor(int configEpoch, int width, int height) {
        return StreamDescriptor.builder(STREAM_KEY, configEpoch,
                        FakeDeferredCodec.DEFAULT_CODEC_ID, FakeDeferredCodec.DEFAULT_MIME,
                        BitstreamFormat.ANNEX_B)
                .profile("high")
                .level("4.1")
                .codedSize(width, height)
                .visibleSize(width, height)
                .build();
    }

    private static StreamDescriptor fakeDescriptor(int configEpoch, int width, int height) {
        return StreamDescriptor.builder(STREAM_KEY, configEpoch, FakeCodec.DEFAULT_CODEC_ID,
                        FakeCodec.DEFAULT_MIME, BitstreamFormat.ANNEX_B)
                .codedSize(width, height)
                .visibleSize(width, height)
                .build();
    }

    private static StreamRuntime newRuntime(FakeDeferredCodec codec,
                                            StreamDescriptor descriptor) {
        return newRuntime(registryOf(codec.registration()), descriptor);
    }

    private static StreamRuntime newRuntime(FakeCodec codec, StreamDescriptor descriptor) {
        return newRuntime(registryOf(codec.registration()), descriptor);
    }

    private static CodecRegistry registryOf(CodecRegistration registration) {
        CodecRegistry registry = new CodecRegistry();
        registry.register(registration);
        return registry;
    }

    private static StreamRuntime newRuntime(CodecRegistry registry, StreamDescriptor descriptor) {
        return new StreamRuntime(registry, STREAM_KEY, descriptor, StreamParameters.DEFAULT,
                new MutableClock(), JOIN_INTERVAL_MS);
    }

    private static CodecRegistration prePublishEmittingRegistration(
            final FakeCodec delegate, final byte[] prePublishPayload) {
        final CodecRegistration source = delegate.registration();
        EncoderFactory factory = new EncoderFactory() {
            @Override
            public Encoder create(StreamKey streamKey, final StreamDescriptor descriptor,
                                  StreamParameters parameters, EncoderOutput output) {
                final FakeCodec.FakeEncoder inner = (FakeCodec.FakeEncoder)
                        source.getEncoderFactory().create(streamKey, descriptor, parameters,
                                output);
                return new Encoder() {
                    @Override
                    public void start() {
                        inner.emit(descriptor.getConfigEpoch(), false, prePublishPayload);
                        inner.start();
                    }

                    @Override
                    public void stop() {
                        inner.stop();
                    }

                    @Override
                    public Surface getInputSurface() {
                        return inner.getInputSurface();
                    }

                    @Override
                    public void updateParameters(StreamParameters newParameters) {
                        inner.updateParameters(newParameters);
                    }

                    @Override
                    public void requestSyncPoint() {
                        inner.requestSyncPoint();
                    }

                    @Override
                    public void close() {
                        inner.close();
                    }
                };
            }
        };
        return new CodecRegistration(source.getDescriptor(), factory,
                source.getDecoderFactory(), source.getPolicy(), 0);
    }

    private static final class RecordingListener implements StreamRuntimeListener {

        private final List<String> events = new ArrayList<>();

        private final List<EncodedAccessUnit> accessUnits = new ArrayList<>();

        private final List<StreamDescriptor> descriptors = new ArrayList<>();

        private final List<StreamKey> failedKeys = new ArrayList<>();

        private final List<EncoderException> failures = new ArrayList<>();

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

        @Override
        public void onEncoderFailed(StreamKey streamKey, EncoderException failure) {
            events.add("failed");
            failedKeys.add(streamKey);
            failures.add(failure);
        }
    }
}
