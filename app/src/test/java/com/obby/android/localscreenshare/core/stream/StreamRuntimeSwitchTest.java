package com.obby.android.localscreenshare.core.stream;

import com.obby.android.localscreenshare.core.codec.CodecIds;
import com.obby.android.localscreenshare.core.codec.CodecRegistry;
import com.obby.android.localscreenshare.core.codec.EncoderException;
import com.obby.android.localscreenshare.core.codec.h264.H264Codec;
import com.obby.android.localscreenshare.core.model.BitstreamFormat;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.model.StreamParameters;
import com.obby.android.localscreenshare.core.support.FakeMediaCodecBridgeFactory;
import com.obby.android.localscreenshare.core.support.FakeMediaCodecEncoderBridge;
import com.obby.android.localscreenshare.core.support.H264Fixtures;
import com.obby.android.localscreenshare.core.support.MutableClock;
import com.obby.android.localscreenshare.core.support.TestH264Registration;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Regression tests for descriptor switches that race an encoder drain thread.
 *
 * <p>The device failure these tests pin down: {@code switchDescriptor} used to stop the old encoder
 * while holding the runtime lock, whereas the old encoder's drain thread enters
 * {@code onAccessUnit} and needs that same lock. The caller therefore waited a full second for a
 * thread that was waiting for the caller, the bounded join expired with {@code EncoderException:
 * drain thread did not terminate within 1000ms}, and the capture display kept rendering into the
 * input surface of an encoder that had already been told to stop, which produced an endless stream
 * of {@code dequeueBuffer -19} errors.</p>
 *
 * <p>The encoder is the production {@link com.obby.android.localscreenshare.core.codec.h264.H264Encoder}
 * over the JVM bridge fake, because the race only exists when frames are drained on the encoder's
 * own thread. No wall-clock threshold is asserted: a switch either returns or throws, and the old
 * failure mode is the exception.</p>
 */
public class StreamRuntimeSwitchTest {

    private static final StreamKey STREAM_KEY = new StreamKey("switch", "screen");

    private static final int WIDTH = 640;

    private static final int HEIGHT = 480;

    private static final int NEXT_WIDTH = 320;

    private static final int NEXT_HEIGHT = 240;

    private static final long AWAIT_TIMEOUT_MS = 5_000L;

    private static final long FEED_INTERVAL_US = 33_000L;

    @Test
    public void switchDescriptor_whileDrainThreadForwardsAccessUnits_doesNotThrow() {
        FakeMediaCodecBridgeFactory factory = new FakeMediaCodecBridgeFactory();
        StreamRuntime runtime = newRuntime(factory);
        UnitRecorder recorder = new UnitRecorder();
        runtime.addListener(recorder);
        runtime.start();
        FakeMediaCodecEncoderBridge oldBridge = firstBridge(factory);
        publishFirstEpoch(oldBridge);
        assertNotNull("the first epoch must publish before the switch",
                waitForDescriptor(runtime));
        assertTrue("the first epoch must forward units before the switch",
                waitForUnits(recorder, 1));

        // Keep the drain thread busy for the whole switch: every forwarded unit takes the runtime
        // lock, which is exactly what the old implementation waited on while joining the thread.
        AtomicBoolean feeding = new AtomicBoolean(true);
        Thread feeder = new Thread(() -> feedUntilStopped(oldBridge, feeding), "switch-test-feeder");
        feeder.setDaemon(true);
        feeder.start();
        int unitsBeforeSwitch = recorder.getAccessUnitCount();
        try {
            runtime.switchDescriptor(descriptor(2, NEXT_WIDTH, NEXT_HEIGHT));
        } catch (RuntimeException e) {
            fail("a switch must not fail because the drain thread is forwarding units: " + e);
        } finally {
            feeding.set(false);
            joinQuietly(feeder);
        }

        assertEquals(2, runtime.getConfigEpoch());
        assertFalse(runtime.isClosed());
        assertEquals(1, oldBridge.getStopCount());
        assertTrue("units must have been forwarded before the switch",
                unitsBeforeSwitch > 0);

        // The superseded encoder is stopped but deliberately not released yet: the capture display
        // is only re-pointed after switchDescriptor returns, and releasing the surface before that
        // is what produced the dequeueBuffer -19 storm on the device.
        assertEquals(0, oldBridge.getReleaseCount());
        runtime.close();
    }

    @Test
    public void switchDescriptor_whileDraining_publishesTheNextEpochAndReleasesTheOldEncoder() {
        FakeMediaCodecBridgeFactory factory = new FakeMediaCodecBridgeFactory();
        StreamRuntime runtime = newRuntime(factory);
        runDrainingSwitch(factory, runtime);
        FakeMediaCodecEncoderBridge oldBridge = firstBridge(factory);
        FakeMediaCodecEncoderBridge newBridge = lastBridge(factory);

        // The old input surface must survive until the new epoch's first join point, so the display
        // has been re-pointed by the time the surface is released.
        assertEquals(0, oldBridge.getReleaseCount());
        newBridge.enqueueFormatChange(H264Fixtures.sps(), H264Fixtures.pps(),
                NEXT_WIDTH, NEXT_HEIGHT);
        newBridge.enqueueSample(H264Fixtures.annexB(H264Fixtures.idrSlice()), 0L, true, false);

        assertEquals(NEXT_WIDTH, waitForDescriptor(runtime).getCodedWidth());
        assertTrue("the retired encoder must be closed after the first join point of the new epoch",
                waitForRelease(oldBridge));
        runtime.close();
    }

    @Test
    public void switchDescriptor_secondSwitchAfterADrainRace_stillAdvancesTheEpoch() {
        FakeMediaCodecBridgeFactory factory = new FakeMediaCodecBridgeFactory();
        StreamRuntime runtime = newRuntime(factory);
        runDrainingSwitch(factory, runtime);

        FakeMediaCodecEncoderBridge newBridge = lastBridge(factory);
        newBridge.enqueueFormatChange(H264Fixtures.sps(), H264Fixtures.pps(),
                NEXT_WIDTH, NEXT_HEIGHT);
        newBridge.enqueueSample(H264Fixtures.annexB(H264Fixtures.idrSlice()), 0L, true, false);
        waitForDescriptor(runtime);

        // A second switch models the real retry: it must be accepted right away, without the
        // runtime waiting for a join that already timed out.
        runtime.switchDescriptor(descriptor(3, WIDTH, HEIGHT));

        assertEquals(3, runtime.getConfigEpoch());
        assertFalse(runtime.isClosed());
        assertEquals(3, factory.getCreatedEncoders().size());
        runtime.close();
    }

    /**
     * Runs one switch against a draining encoder and leaves the runtime on epoch 2.
     */
    private static void runDrainingSwitch(FakeMediaCodecBridgeFactory factory,
                                          StreamRuntime runtime) {
        runtime.start();
        FakeMediaCodecEncoderBridge oldBridge = firstBridge(factory);
        publishFirstEpoch(oldBridge);
        assertNotNull(waitForDescriptor(runtime));

        AtomicBoolean feeding = new AtomicBoolean(true);
        Thread feeder = new Thread(() -> feedUntilStopped(oldBridge, feeding), "switch-test-feeder");
        feeder.setDaemon(true);
        feeder.start();
        try {
            runtime.switchDescriptor(descriptor(2, NEXT_WIDTH, NEXT_HEIGHT));
        } finally {
            feeding.set(false);
            joinQuietly(feeder);
        }
        assertEquals(2, runtime.getConfigEpoch());
    }

    private static void publishFirstEpoch(FakeMediaCodecEncoderBridge bridge) {
        bridge.enqueueFormatChange(H264Fixtures.sps(), H264Fixtures.pps(), WIDTH, HEIGHT);
        bridge.enqueueSample(H264Fixtures.annexB(H264Fixtures.idrSlice()), 0L, true, false);
    }

    private static void feedUntilStopped(FakeMediaCodecEncoderBridge bridge, AtomicBoolean feeding) {
        byte[] payload = H264Fixtures.annexB(H264Fixtures.pSlice());
        long ptsUs = FEED_INTERVAL_US;
        while (feeding.get()) {
            bridge.enqueueSample(payload, ptsUs, false, false);
            ptsUs += FEED_INTERVAL_US;
            Thread.yield();
        }
    }

    private static StreamRuntime newRuntime(FakeMediaCodecBridgeFactory factory) {
        CodecRegistry registry = new CodecRegistry();
        registry.register(TestH264Registration.withoutSurfaceRequirement(factory));
        return new StreamRuntime(registry, STREAM_KEY, descriptor(1, WIDTH, HEIGHT),
                StreamParameters.DEFAULT, new MutableClock(), 0L);
    }

    private static StreamDescriptor descriptor(int configEpoch, int width, int height) {
        return StreamDescriptor.builder(STREAM_KEY, configEpoch, CodecIds.H264, H264Codec.MIME,
                        BitstreamFormat.ANNEX_B)
                .codedSize(width, height)
                .visibleSize(width, height)
                .build();
    }

    private static FakeMediaCodecEncoderBridge firstBridge(FakeMediaCodecBridgeFactory factory) {
        return factory.getCreatedEncoders().get(0);
    }

    private static FakeMediaCodecEncoderBridge lastBridge(FakeMediaCodecBridgeFactory factory) {
        List<FakeMediaCodecEncoderBridge> created = factory.getCreatedEncoders();
        return created.get(created.size() - 1);
    }

    private static StreamDescriptor waitForDescriptor(StreamRuntime runtime) {
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            StreamDescriptor published = runtime.getDescriptor();
            if (published != null) {
                return published;
            }
            sleep();
        }
        fail("the descriptor was never published");
        return null;
    }

    private static boolean waitForUnits(UnitRecorder recorder, int expected) {
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (recorder.getAccessUnitCount() >= expected) {
                return true;
            }
            sleep();
        }
        return false;
    }

    private static boolean waitForRelease(FakeMediaCodecEncoderBridge bridge) {
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (bridge.getReleaseCount() > 0) {
                return true;
            }
            sleep();
        }
        return false;
    }

    private static void sleep() {
        try {
            Thread.sleep(10L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(AWAIT_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Counts forwarded units so a test can show that the drain thread kept working.
     */
    private static final class UnitRecorder implements StreamRuntimeListener {

        private final AtomicInteger accessUnitCount = new AtomicInteger();

        @Override
        public void onAccessUnit(
                com.obby.android.localscreenshare.core.model.EncodedAccessUnit accessUnit) {
            accessUnitCount.incrementAndGet();
        }

        @Override
        public void onDescriptorChanged(StreamDescriptor descriptor) {
        }

        @Override
        public void onEncoderFailed(StreamKey streamKey, EncoderException failure) {
        }

        int getAccessUnitCount() {
            return accessUnitCount.get();
        }
    }
}
