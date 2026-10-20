package com.obby.android.localscreenshare.core.codec.mediacodec;

import android.view.Surface;

import com.obby.android.localscreenshare.core.model.ColorInfo;
import com.obby.android.localscreenshare.core.support.FakeMediaCodecBridgeFactory;
import com.obby.android.localscreenshare.core.support.FakeMediaCodecDecoderBridge;
import com.obby.android.localscreenshare.core.support.FakeMediaCodecEncoderBridge;

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class MediaCodecBridgeContractTest {

    private static final String MIME = "video/avc";

    private static final ColorInfo COLOR_INFO = new ColorInfo("bt709", "sdr", true);

    private static EncoderFormat encoderFormat() {
        return new EncoderFormat(640, 480, 2_000_000, 30, 2);
    }

    private static DecoderFormat decoderFormat() {
        return new DecoderFormat(640, 480, csd0(), csd1(), null);
    }

    private static byte[] csd0() {
        return new byte[]{0, 0, 0, 1, 0x67};
    }

    private static byte[] csd1() {
        return new byte[]{0, 0, 0, 1, 0x68};
    }

    @Test
    public void fakeEncoder_recordsConfigureAndLifecycle() {
        FakeMediaCodecEncoderBridge bridge = new FakeMediaCodecEncoderBridge();
        EncoderFormat format = encoderFormat();

        assertEquals(0, bridge.getConfigureCount());
        assertNull(bridge.getLastConfigureFormat());
        assertNull(bridge.getOutputFormat());

        bridge.configure(format);

        assertEquals(1, bridge.getConfigureCount());
        assertSame(format, bridge.getLastConfigureFormat());
        assertNull(bridge.createInputSurface());
        assertEquals(1, bridge.getCreateInputSurfaceCount());

        bridge.start();
        bridge.start();
        assertEquals(2, bridge.getStartCount());

        bridge.stop();
        assertEquals(1, bridge.getStopCount());

        bridge.updateBitrate(1_500_000);
        bridge.updateBitrate(900_000);
        assertEquals(Arrays.asList(1_500_000, 900_000), bridge.getBitrateUpdates());

        bridge.release();
        assertEquals(1, bridge.getReleaseCount());
    }

    @Test
    public void fakeEncoder_returnsNullOnDequeueTimeout() {
        FakeMediaCodecEncoderBridge bridge = new FakeMediaCodecEncoderBridge();

        assertNull(bridge.dequeueOutput(1_000L));

        bridge.enqueueSample(new byte[]{1, 2}, 10L, true, false);

        EncodedSample sample = bridge.dequeueOutput(1_000L);
        assertNotNull(sample);
        assertEquals(10L, sample.getPtsUs());
        assertArrayEquals(new byte[]{1, 2}, sample.getData());
        assertTrue(sample.isKeyFrame());
        assertFalse(sample.isCodecConfig());
        assertFalse(sample.isFormatChanged());
        assertFalse(sample.isEndOfStream());
        assertNull(bridge.dequeueOutput(0L));
    }

    @Test
    public void fakeEncoder_emitsFormatChangeThenSamples() {
        FakeMediaCodecEncoderBridge bridge = new FakeMediaCodecEncoderBridge();

        assertNull(bridge.getOutputFormat());

        bridge.enqueueFormatChange(csd0(), csd1(), 1280, 720);

        EncoderStreamFormat format = bridge.getOutputFormat();
        assertNotNull(format);
        assertArrayEquals(csd0(), format.getCsd0());
        assertArrayEquals(csd1(), format.getCsd1());
        assertEquals(1280, format.getWidth());
        assertEquals(720, format.getHeight());
        assertSame(ColorInfo.UNSPECIFIED, format.getColorInfo());
        assertEquals(1, bridge.getPendingSampleCount());

        EncodedSample formatEvent = bridge.dequeueOutput(1_000L);
        assertNotNull(formatEvent);
        assertTrue(formatEvent.isFormatChanged());
        assertFalse(formatEvent.isKeyFrame());
        assertFalse(formatEvent.isCodecConfig());
        assertFalse(formatEvent.isEndOfStream());
        assertEquals(0, formatEvent.getData().length);
        assertEquals(0L, formatEvent.getPtsUs());

        bridge.enqueueSample(new byte[]{0x65}, 100L, true, false);
        bridge.enqueueSample(new byte[]{0x41}, 200L, false, false);

        EncodedSample first = bridge.dequeueOutput(1_000L);
        EncodedSample second = bridge.dequeueOutput(1_000L);
        assertNotNull(first);
        assertNotNull(second);
        assertEquals(100L, first.getPtsUs());
        assertEquals(200L, second.getPtsUs());
        assertArrayEquals(new byte[]{0x65}, first.getData());
        assertArrayEquals(new byte[]{0x41}, second.getData());
        assertFalse(first.isFormatChanged());
        assertFalse(second.isFormatChanged());
        assertNull(bridge.dequeueOutput(0L));
    }

    @Test
    public void fakeEncoder_reportsCodecConfigAndKeyFrameFlags() {
        FakeMediaCodecEncoderBridge bridge = new FakeMediaCodecEncoderBridge();

        bridge.enqueueSample(new byte[]{0x67}, 1L, false, true);
        bridge.enqueueSample(new byte[]{0x65}, 2L, true, false);
        bridge.enqueueSample(new byte[]{0x41}, 3L, false, false);
        bridge.enqueueSample(new byte[0], 4L, false, false, false, true);

        EncodedSample config = bridge.dequeueOutput(1_000L);
        EncodedSample keyFrame = bridge.dequeueOutput(1_000L);
        EncodedSample delta = bridge.dequeueOutput(1_000L);
        EncodedSample endOfStream = bridge.dequeueOutput(1_000L);
        assertNotNull(config);
        assertNotNull(keyFrame);
        assertNotNull(delta);
        assertNotNull(endOfStream);

        assertTrue(config.isCodecConfig());
        assertFalse(config.isKeyFrame());
        assertFalse(config.isFormatChanged());
        assertFalse(config.isEndOfStream());

        assertFalse(keyFrame.isCodecConfig());
        assertTrue(keyFrame.isKeyFrame());
        assertFalse(keyFrame.isFormatChanged());
        assertFalse(keyFrame.isEndOfStream());

        assertFalse(delta.isCodecConfig());
        assertFalse(delta.isKeyFrame());
        assertFalse(delta.isFormatChanged());
        assertFalse(delta.isEndOfStream());

        assertFalse(endOfStream.isCodecConfig());
        assertFalse(endOfStream.isKeyFrame());
        assertFalse(endOfStream.isFormatChanged());
        assertTrue(endOfStream.isEndOfStream());
        assertEquals(4L, endOfStream.getPtsUs());
    }

    @Test
    public void fakeEncoder_injectsConfigureCreateSurfaceAndStartFailures() {
        FakeMediaCodecEncoderBridge bridge = new FakeMediaCodecEncoderBridge();
        EncoderFormat format = encoderFormat();

        RuntimeException configureFailure = new IllegalStateException("configure failed");
        bridge.failNextConfigure(configureFailure);
        assertSame(configureFailure,
                assertThrows(IllegalStateException.class, () -> bridge.configure(format)));
        assertEquals(0, bridge.getConfigureCount());
        assertNull(bridge.getLastConfigureFormat());
        bridge.configure(format);
        assertEquals(1, bridge.getConfigureCount());
        assertSame(format, bridge.getLastConfigureFormat());

        RuntimeException surfaceFailure = new IllegalArgumentException("surface failed");
        bridge.failNextCreateInputSurface(surfaceFailure);
        assertSame(surfaceFailure,
                assertThrows(IllegalArgumentException.class, bridge::createInputSurface));
        assertEquals(0, bridge.getCreateInputSurfaceCount());
        assertNull(bridge.createInputSurface());
        assertEquals(1, bridge.getCreateInputSurfaceCount());

        RuntimeException startFailure = new IllegalStateException("start failed");
        bridge.failNextStart(startFailure);
        assertSame(startFailure, assertThrows(IllegalStateException.class, bridge::start));
        assertEquals(0, bridge.getStartCount());
        bridge.start();
        assertEquals(1, bridge.getStartCount());
    }

    @Test
    public void fakeEncoder_injectsDequeueFailureOnce() {
        FakeMediaCodecEncoderBridge bridge = new FakeMediaCodecEncoderBridge();
        bridge.enqueueSample(new byte[]{1}, 5L, true, false);

        RuntimeException failure = new IllegalStateException("dequeue failed");
        bridge.failNextDequeue(failure);
        assertSame(failure,
                assertThrows(IllegalStateException.class, () -> bridge.dequeueOutput(1_000L)));

        EncodedSample recovered = bridge.dequeueOutput(1_000L);
        assertNotNull(recovered);
        assertEquals(5L, recovered.getPtsUs());
    }

    @Test
    public void fakeEncoder_autoEmitsSyncFrameOnRequest() {
        FakeMediaCodecEncoderBridge bridge = new FakeMediaCodecEncoderBridge();

        bridge.requestSyncFrame();
        assertEquals(1, bridge.getRequestSyncFrameCount());
        assertEquals(0, bridge.getPendingSampleCount());

        bridge.setSyncResponse(new byte[]{0x65, 0x01});
        bridge.requestSyncFrame();
        bridge.requestSyncFrame();
        assertEquals(3, bridge.getRequestSyncFrameCount());
        assertEquals(2, bridge.getPendingSampleCount());

        EncodedSample first = bridge.dequeueOutput(1_000L);
        EncodedSample second = bridge.dequeueOutput(1_000L);
        assertNotNull(first);
        assertNotNull(second);
        assertTrue(first.isKeyFrame());
        assertFalse(first.isCodecConfig());
        assertFalse(first.isFormatChanged());
        assertFalse(first.isEndOfStream());
        assertArrayEquals(new byte[]{0x65, 0x01}, first.getData());
        assertTrue(second.isKeyFrame());
        assertTrue(second.getPtsUs() > first.getPtsUs());

        bridge.setSyncResponse(null);
        bridge.requestSyncFrame();
        assertEquals(4, bridge.getRequestSyncFrameCount());
        assertEquals(0, bridge.getPendingSampleCount());
    }

    @Test
    public void fakeEncoder_blockNextDequeueHoldsUntilRelease() throws Exception {
        FakeMediaCodecEncoderBridge bridge = new FakeMediaCodecEncoderBridge();
        bridge.enqueueSample(new byte[]{7}, 9L, true, false);
        bridge.blockNextDequeue();

        AtomicReference<EncodedSample> result = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            started.countDown();
            result.set(bridge.dequeueOutput(20_000L));
        }, "blocked-dequeue");
        worker.start();

        assertTrue(started.await(1L, TimeUnit.SECONDS));
        Thread.sleep(300L);
        assertTrue("dequeue must still be blocked", worker.isAlive());

        bridge.releaseBlockedDequeue();
        worker.join(5_000L);

        assertFalse("dequeue must finish after release", worker.isAlive());
        assertNotNull(result.get());
        assertArrayEquals(new byte[]{7}, result.get().getData());
    }

    @Test
    public void fakeDecoder_recordsConfigureAndQueuedInput() {
        FakeMediaCodecDecoderBridge bridge = new FakeMediaCodecDecoderBridge();
        DecoderFormat format = decoderFormat();

        bridge.configure(format);
        assertEquals(1, bridge.getConfigureCount());
        assertSame(format, bridge.getLastConfigureFormat());

        bridge.start();
        assertEquals(1, bridge.getStartCount());

        assertEquals(0, bridge.dequeueInputBuffer(0L));
        assertEquals(1, bridge.dequeueInputBuffer(1_000L));

        byte[] inputData = {0x67, 0x42};
        bridge.queueInputBuffer(2, inputData, 33L);
        inputData[0] = 9;

        assertEquals(Arrays.asList(2), bridge.getQueuedInputIndices());
        assertEquals(Arrays.asList(33L), bridge.getQueuedInputPtsUs());
        assertEquals(1, bridge.getQueuedInputData().size());
        assertArrayEquals(new byte[]{0x67, 0x42}, bridge.getQueuedInputData().get(0));

        byte[] exposed = bridge.getQueuedInputData().get(0);
        exposed[0] = 9;
        assertArrayEquals(new byte[]{0x67, 0x42}, bridge.getQueuedInputData().get(0));

        bridge.stop();
        bridge.release();
        assertEquals(1, bridge.getStopCount());
        assertEquals(1, bridge.getReleaseCount());
    }

    @Test
    public void fakeDecoder_recordsOutputSurfaceFromDecoderFormat() throws Exception {
        byte[] sourceCsd0 = csd0();
        byte[] sourceCsd1 = csd1();
        DecoderFormat format = new DecoderFormat(640, 480, sourceCsd0, sourceCsd1, null);
        sourceCsd0[4] = 9;
        sourceCsd1[4] = 9;

        FakeMediaCodecDecoderBridge bridge = new FakeMediaCodecDecoderBridge();
        bridge.configure(format);

        assertSame(format, bridge.getLastConfigureFormat());
        // A JVM unit test cannot instantiate android.view.Surface; the fake must carry the
        // reference it was given (here null) without touching the Android runtime.
        assertNull(bridge.getLastConfigureFormat().getOutputSurface());
        assertArrayEquals(csd0(), bridge.getLastConfigureFormat().getCsd0());
        assertArrayEquals(csd1(), bridge.getLastConfigureFormat().getCsd1());
        assertEquals(640, bridge.getLastConfigureFormat().getWidth());
        assertEquals(480, bridge.getLastConfigureFormat().getHeight());

        assertSingleConstructor(DecoderFormat.class, int.class, int.class, byte[].class,
                byte[].class, Surface.class);
        assertSame(int.class, DecoderFormat.class.getMethod("getWidth").getReturnType());
        assertSame(int.class, DecoderFormat.class.getMethod("getHeight").getReturnType());
        assertSame(byte[].class, DecoderFormat.class.getMethod("getCsd0").getReturnType());
        assertSame(byte[].class, DecoderFormat.class.getMethod("getCsd1").getReturnType());
        assertSame(Surface.class, DecoderFormat.class.getMethod("getOutputSurface").getReturnType());
        assertFalse(hasPublicMethod(DecoderFormat.class, "setOutputSurface"));
        assertFalse(hasPublicMethod(DecoderFormat.class, "getColorInfo"));
    }

    @Test
    public void fakeDecoder_reportsOutputAndInputTimeout() {
        FakeMediaCodecDecoderBridge bridge = new FakeMediaCodecDecoderBridge();

        bridge.failNextInputTimeout();
        assertEquals(-1, bridge.dequeueInputBuffer(5_000L));
        assertEquals(0, bridge.dequeueInputBuffer(5_000L));

        byte[] input = {0x41};
        RuntimeException queueFailure = new IllegalStateException("queue failed");
        bridge.failNextQueue(queueFailure);
        assertSame(queueFailure,
                assertThrows(IllegalStateException.class,
                        () -> bridge.queueInputBuffer(0, input, 12L)));
        assertTrue(bridge.getQueuedInputIndices().isEmpty());
        assertTrue(bridge.getQueuedInputData().isEmpty());
        assertTrue(bridge.getQueuedInputPtsUs().isEmpty());

        bridge.queueInputBuffer(0, input, 12L);
        assertEquals(1, bridge.getQueuedInputIndices().size());
        assertEquals(Arrays.asList(12L), bridge.getQueuedInputPtsUs());

        assertNull(bridge.dequeueOutput(1_000L));

        DecodedOutput output = new DecodedOutput(77L, false, false);
        bridge.enqueueOutput(output);

        RuntimeException dequeueFailure = new IllegalStateException("dequeue failed");
        bridge.failNextDequeue(dequeueFailure);
        assertSame(dequeueFailure,
                assertThrows(IllegalStateException.class, () -> bridge.dequeueOutput(1_000L)));

        DecodedOutput recovered = bridge.dequeueOutput(1_000L);
        assertSame(output, recovered);
        assertEquals(77L, recovered.getPtsUs());
        assertFalse(recovered.isFormatChanged());
        assertFalse(recovered.isEndOfStream());
        assertNull(bridge.dequeueOutput(0L));
    }

    @Test
    public void fakeDecoder_injectsConfigureAndStartFailures() {
        FakeMediaCodecDecoderBridge bridge = new FakeMediaCodecDecoderBridge();
        DecoderFormat format = decoderFormat();

        RuntimeException configureFailure = new IllegalArgumentException("configure failed");
        bridge.failNextConfigure(configureFailure);
        assertSame(configureFailure,
                assertThrows(IllegalArgumentException.class, () -> bridge.configure(format)));
        assertEquals(0, bridge.getConfigureCount());
        assertNull(bridge.getLastConfigureFormat());
        bridge.configure(format);
        assertEquals(1, bridge.getConfigureCount());
        assertSame(format, bridge.getLastConfigureFormat());

        RuntimeException startFailure = new IllegalStateException("start failed");
        bridge.failNextStart(startFailure);
        assertSame(startFailure, assertThrows(IllegalStateException.class, bridge::start));
        assertEquals(0, bridge.getStartCount());
        bridge.start();
        assertEquals(1, bridge.getStartCount());
    }

    @Test
    public void fakeDecoder_reportsFormatChangeAndEndOfStreamOutputs() {
        FakeMediaCodecDecoderBridge bridge = new FakeMediaCodecDecoderBridge();
        DecodedOutput formatChanged = new DecodedOutput(100L, true, false);
        DecodedOutput endOfStream = new DecodedOutput(200L, false, true);

        bridge.enqueueOutput(formatChanged);
        bridge.enqueueOutput(endOfStream);
        assertEquals(2, bridge.getPendingOutputCount());

        DecodedOutput first = bridge.dequeueOutput(1_000L);
        DecodedOutput second = bridge.dequeueOutput(1_000L);
        assertSame(formatChanged, first);
        assertSame(endOfStream, second);
        assertTrue(first.isFormatChanged());
        assertFalse(first.isEndOfStream());
        assertFalse(second.isFormatChanged());
        assertTrue(second.isEndOfStream());
        assertEquals(100L, first.getPtsUs());
        assertEquals(200L, second.getPtsUs());
        assertNull(bridge.dequeueOutput(0L));
    }

    @Test
    public void bridgeFactory_createsIndependentEncoderAndDecoder() {
        FakeMediaCodecBridgeFactory factory = new FakeMediaCodecBridgeFactory();
        MediaCodecBridgeFactory contractFactory = factory;

        MediaCodecEncoderBridge firstEncoder = contractFactory.createEncoder(MIME);
        MediaCodecEncoderBridge secondEncoder = contractFactory.createEncoder("video/hevc");
        MediaCodecDecoderBridge firstDecoder = contractFactory.createDecoder(MIME);
        MediaCodecDecoderBridge secondDecoder = contractFactory.createDecoder(MIME);

        assertTrue(firstEncoder instanceof FakeMediaCodecEncoderBridge);
        assertTrue(firstDecoder instanceof FakeMediaCodecDecoderBridge);
        assertNotSame(firstEncoder, secondEncoder);
        assertNotSame(firstDecoder, secondDecoder);
        assertNotSame(firstEncoder, firstDecoder);

        assertSame(secondEncoder, factory.getLastEncoder());
        assertSame(secondDecoder, factory.getLastDecoder());
        assertEquals("video/hevc", factory.getLastEncoderMime());
        assertEquals(MIME, factory.getLastDecoderMime());
        assertEquals(2, factory.getCreatedEncoders().size());
        assertEquals(2, factory.getCreatedDecoders().size());
        assertSame(firstEncoder, factory.getCreatedEncoders().get(0));
        assertSame(firstDecoder, factory.getCreatedDecoders().get(0));
        assertEquals(Arrays.asList(MIME, "video/hevc"), factory.getCreatedEncoderMimes());
        assertEquals(Arrays.asList(MIME, MIME), factory.getCreatedDecoderMimes());
    }

    @Test
    public void bridgeDataClasses_haveClosedGettersAndDefensiveCopies() {
        EncoderFormat encoderFormat = encoderFormat();
        assertEquals(640, encoderFormat.getWidth());
        assertEquals(480, encoderFormat.getHeight());
        assertEquals(2_000_000, encoderFormat.getBitrateBps());
        assertEquals(30, encoderFormat.getFrameRate());
        assertEquals(2, encoderFormat.getKeyFrameIntervalSeconds());
        assertSingleConstructor(EncoderFormat.class, int.class, int.class, int.class, int.class,
                int.class);
        assertClosedGetters(EncoderFormat.class, "getWidth", "getHeight", "getBitrateBps",
                "getFrameRate", "getKeyFrameIntervalSeconds");
        assertFalse(hasPublicMethod(EncoderFormat.class, "getColorInfo"));

        byte[] decoderCsd0 = csd0();
        byte[] decoderCsd1 = csd1();
        DecoderFormat decoderFormat = new DecoderFormat(640, 480, decoderCsd0, decoderCsd1, null);
        decoderCsd0[4] = 9;
        decoderCsd1[4] = 9;
        assertArrayEquals(csd0(), decoderFormat.getCsd0());
        assertArrayEquals(csd1(), decoderFormat.getCsd1());
        byte[] exposedCsd0 = decoderFormat.getCsd0();
        exposedCsd0[0] = 9;
        assertArrayEquals(csd0(), decoderFormat.getCsd0());
        assertEquals(640, decoderFormat.getWidth());
        assertEquals(480, decoderFormat.getHeight());
        assertNull(decoderFormat.getOutputSurface());
        assertSingleConstructor(DecoderFormat.class, int.class, int.class, byte[].class,
                byte[].class, Surface.class);
        assertClosedGetters(DecoderFormat.class, "getWidth", "getHeight", "getCsd0", "getCsd1",
                "getOutputSurface");

        byte[] samplePayload = {1, 2, 3};
        EncodedSample sample = new EncodedSample(samplePayload, 120L, true, true, true, true);
        samplePayload[0] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, sample.getData());
        byte[] exposedSample = sample.getData();
        exposedSample[1] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, sample.getData());
        assertEquals(120L, sample.getPtsUs());
        assertTrue(sample.isKeyFrame());
        assertTrue(sample.isCodecConfig());
        assertTrue(sample.isFormatChanged());
        assertTrue(sample.isEndOfStream());
        assertSingleConstructor(EncodedSample.class, byte[].class, long.class, boolean.class,
                boolean.class, boolean.class, boolean.class);
        assertClosedGetters(EncodedSample.class, "getData", "getPtsUs", "isKeyFrame",
                "isCodecConfig", "isFormatChanged", "isEndOfStream");

        byte[] streamCsd0 = csd0();
        byte[] streamCsd1 = csd1();
        EncoderStreamFormat streamFormat = new EncoderStreamFormat(streamCsd0, streamCsd1, 0, -1,
                COLOR_INFO);
        streamCsd0[4] = 9;
        streamCsd1[4] = 9;
        assertArrayEquals(csd0(), streamFormat.getCsd0());
        assertArrayEquals(csd1(), streamFormat.getCsd1());
        byte[] exposedStreamCsd1 = streamFormat.getCsd1();
        exposedStreamCsd1[0] = 9;
        assertArrayEquals(csd1(), streamFormat.getCsd1());
        // EncoderStreamFormat carries data only; illegal dimensions are rejected by H264Encoder.
        assertEquals(0, streamFormat.getWidth());
        assertEquals(-1, streamFormat.getHeight());
        assertSame(COLOR_INFO, streamFormat.getColorInfo());
        assertSingleConstructor(EncoderStreamFormat.class, byte[].class, byte[].class, int.class,
                int.class, ColorInfo.class);
        assertClosedGetters(EncoderStreamFormat.class, "getCsd0", "getCsd1", "getWidth",
                "getHeight", "getColorInfo");

        DecodedOutput output = new DecodedOutput(55L, true, false);
        assertEquals(55L, output.getPtsUs());
        assertTrue(output.isFormatChanged());
        assertFalse(output.isEndOfStream());
        DecodedOutput endOfStream = new DecodedOutput(66L, false, true);
        assertFalse(endOfStream.isFormatChanged());
        assertTrue(endOfStream.isEndOfStream());
        assertSingleConstructor(DecodedOutput.class, long.class, boolean.class, boolean.class);
        assertClosedGetters(DecodedOutput.class, "getPtsUs", "isFormatChanged", "isEndOfStream");

        assertClosedImmutable(EncoderFormat.class);
        assertClosedImmutable(DecoderFormat.class);
        assertClosedImmutable(EncodedSample.class);
        assertClosedImmutable(EncoderStreamFormat.class);
        assertClosedImmutable(DecodedOutput.class);
    }

    private static void assertSingleConstructor(Class<?> type, Class<?>... parameterTypes) {
        Constructor<?>[] constructors = type.getConstructors();
        assertEquals(type.getSimpleName() + " must expose exactly one public constructor",
                1, constructors.length);
        assertArrayEquals(parameterTypes, constructors[0].getParameterTypes());
    }

    private static void assertClosedGetters(Class<?> type, String... expectedGetters) {
        Set<String> actualGetters = new TreeSet<>();
        for (Method method : type.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())
                    || Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            if (method.getName().startsWith("get") || method.getName().startsWith("is")) {
                actualGetters.add(method.getName());
            }
        }
        assertEquals(type.getSimpleName() + " getters",
                new TreeSet<>(Arrays.asList(expectedGetters)), actualGetters);
    }

    private static boolean hasPublicMethod(Class<?> type, String name) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static void assertClosedImmutable(Class<?> type) {
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            String name = type.getSimpleName() + "." + field.getName();
            assertTrue(name + " must be private", Modifier.isPrivate(field.getModifiers()));
            assertTrue(name + " must be final", Modifier.isFinal(field.getModifiers()));
        }
        for (Method method : type.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            assertFalse(type.getSimpleName() + " must not expose setters: " + method.getName(),
                    method.getName().startsWith("set"));
        }
    }
}
