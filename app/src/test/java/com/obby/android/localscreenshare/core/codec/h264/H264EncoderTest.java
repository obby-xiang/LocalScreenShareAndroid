package com.obby.android.localscreenshare.core.codec.h264;

import com.obby.android.localscreenshare.core.codec.Encoder;
import com.obby.android.localscreenshare.core.codec.EncoderConfig;
import com.obby.android.localscreenshare.core.codec.EncoderException;
import com.obby.android.localscreenshare.core.codec.EncoderOutput;
import com.obby.android.localscreenshare.core.codec.mediacodec.EncodedSample;
import com.obby.android.localscreenshare.core.codec.mediacodec.EncoderFormat;
import com.obby.android.localscreenshare.core.codec.mediacodec.EncoderStreamFormat;
import com.obby.android.localscreenshare.core.codec.mediacodec.MediaCodecBridgeFactory;
import com.obby.android.localscreenshare.core.codec.mediacodec.MediaCodecDecoderBridge;
import com.obby.android.localscreenshare.core.codec.mediacodec.MediaCodecEncoderBridge;
import com.obby.android.localscreenshare.core.model.BitstreamFormat;
import com.obby.android.localscreenshare.core.model.CodecConfigPart;
import com.obby.android.localscreenshare.core.model.ColorInfo;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.model.StreamParameters;
import com.obby.android.localscreenshare.core.support.FakeMediaCodecEncoderBridge;
import com.obby.android.localscreenshare.core.support.H264Fixtures;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Behavioural tests for {@link H264Encoder} driven entirely by the JVM encoder bridge fake.
 *
 * <p>Every asynchronous expectation is observed through bounded waits on the encoder output
 * callbacks; the drain thread is never polled or slept on directly. Samples are synthetic and
 * come from {@link H264Fixtures}.</p>
 */
public class H264EncoderTest {

    private static final String H264_MIME = "video/avc";

    private static final StreamKey STREAM_KEY = new StreamKey("session-5", "stream-5");

    private static final int CONFIG_EPOCH = 3;

    private static final int WIDTH = 640;

    private static final int HEIGHT = 480;

    private static final StreamParameters PARAMETERS =
            new StreamParameters(2_500_000, 60, 1000);

    /**
     * Upper bound for an expected callback; generous because it only guards against hangs.
     */
    private static final long AWAIT_TIMEOUT_MS = 5_000L;

    /**
     * Window that must stay silent when no further callback is expected.
     */
    private static final long QUIET_WINDOW_MS = 250L;

    /**
     * Window used to observe a release that must not happen while {@code bridge.start()} is still
     * blocked. Long enough to defeat scheduling jitter, much shorter than the close timeout.
     */
    private static final long RELEASE_HOLD_WINDOW_MS = 300L;

    private static final ColorInfo BT709 = new ColorInfo("BT709", "SDR", false);

    private static final ColorInfo BT2020 = new ColorInfo("BT2020", "PQ", true);

    @Test
    public void constructor_configuresBridgeAndCreatesInputSurfaceBeforeStart() {
        Harness harness = new Harness();

        H264Encoder encoder = harness.newEncoder();

        assertEquals(1, harness.bridge.getConfigureCount());
        assertEquals(1, harness.bridge.getCreateInputSurfaceCount());
        assertEquals(0, harness.bridge.getStartCount());
        assertEquals(Collections.singletonList(H264_MIME), harness.factory.getEncoderMimes());
        assertFalse(encoder.isBridgeReleased());
        assertFalse(encoder.isLeakDetected());
        encoder.close();
        assertEquals(1, harness.bridge.getReleaseCount());
    }

    @Test
    public void constructor_passesConfigureParametersToBridge() {
        Harness harness = new Harness();

        harness.newEncoder();

        EncoderFormat format = harness.bridge.getLastConfigureFormat();
        assertNotNull(format);
        assertEquals(WIDTH, format.getWidth());
        assertEquals(HEIGHT, format.getHeight());
        assertEquals(2_500_000, format.getBitrateBps());
        assertEquals(60, format.getFrameRate());
        assertEquals(1, format.getKeyFrameIntervalSeconds());
    }

    @Test
    public void constructor_convertsKeyFrameIntervalMsToCeilingSeconds() {
        int[][] cases = {{1000, 1}, {1001, 2}, {250, 1}, {0, 1}};
        for (int[] testCase : cases) {
            Harness harness = new Harness(
                    new StreamParameters(1_000_000, 30, testCase[0]));

            harness.newEncoder();

            EncoderFormat format = harness.bridge.getLastConfigureFormat();
            assertNotNull("missing format for " + testCase[0] + "ms", format);
            assertEquals("key frame interval for " + testCase[0] + "ms",
                    testCase[1], format.getKeyFrameIntervalSeconds());
        }
    }

    @Test
    public void constructor_clampsZeroBitrateAndFrameRateToAtLeastOne() {
        Harness harness = new Harness(new StreamParameters(0, 0, 1000));

        harness.newEncoder();

        EncoderFormat format = harness.bridge.getLastConfigureFormat();
        assertNotNull(format);
        assertEquals(1, format.getBitrateBps());
        assertEquals(1, format.getFrameRate());
    }

    @Test
    public void constructor_configureFailureReleasesBridgeAndThrowsEncoderException() {
        Harness harness = new Harness();
        harness.bridge.failNextConfigure(new RuntimeException("configure failed"));

        assertThrows(EncoderException.class, harness::newEncoder);

        assertEquals(0, harness.bridge.getConfigureCount());
        assertEquals(0, harness.bridge.getCreateInputSurfaceCount());
        assertEquals(0, harness.bridge.getStartCount());
        assertEquals(1, harness.bridge.getReleaseCount());
    }

    @Test
    public void constructor_createInputSurfaceFailureReleasesBridgeAndThrowsEncoderException() {
        Harness harness = new Harness();
        harness.bridge.failNextCreateInputSurface(new RuntimeException("surface failed"));

        assertThrows(EncoderException.class, harness::newEncoder);

        assertEquals(1, harness.bridge.getConfigureCount());
        assertEquals(0, harness.bridge.getCreateInputSurfaceCount());
        assertEquals(0, harness.bridge.getStartCount());
        assertEquals(1, harness.bridge.getReleaseCount());
    }

    @Test
    public void createInputSurface_isCalledDuringConstructionBeforeStart() {
        Harness harness = new Harness();

        H264Encoder encoder = harness.newEncoder();
        assertEquals(1, harness.bridge.getCreateInputSurfaceCount());
        assertEquals(0, harness.bridge.getStartCount());

        encoder.start();

        assertEquals(1, harness.bridge.getCreateInputSurfaceCount());
        assertEquals(1, harness.bridge.getStartCount());
        encoder.close();
    }

    @Test
    public void getInputSurface_returnsCreatedSurfaceReferenceBeforeStart() {
        Harness harness = new Harness();

        H264Encoder encoder = harness.newEncoder();

        assertNull(encoder.getInputSurface());
        assertEquals(1, harness.bridge.getCreateInputSurfaceCount());
        assertEquals(0, harness.bridge.getStartCount());
        encoder.close();
    }

    @Test
    public void start_onlyStartsBridgeAndDrainThread() {
        Harness harness = new Harness();
        H264Encoder encoder = harness.newEncoder();

        encoder.start();

        assertEquals(1, harness.bridge.getStartCount());
        assertEquals(1, harness.bridge.getConfigureCount());
        assertEquals(1, harness.bridge.getCreateInputSurfaceCount());

        harness.bridge.enqueueFormatChange(H264Fixtures.sps(), H264Fixtures.pps(), WIDTH, HEIGHT);
        harness.bridge.enqueueSample(idrSample(), 1_000L, true, false);
        assertNotNull(harness.output.awaitAccessUnit(AWAIT_TIMEOUT_MS));
        encoder.close();
    }

    @Test
    public void start_bridgeStartFailureFailsEncoderAndStopsLoop() {
        Harness harness = new Harness();
        H264Encoder encoder = harness.newEncoder();
        harness.bridge.failNextStart(new RuntimeException("start failed"));

        assertThrows(EncoderException.class, encoder::start);

        assertEquals(0, harness.bridge.getStartCount());
        encoder.close();
        assertEquals(1, harness.bridge.getReleaseCount());
    }

    @Test
    public void start_whenAlreadyStarted_throwsIllegalStateException() {
        Harness harness = new Harness();
        H264Encoder encoder = harness.newStartedEncoder();
        assertEquals(1, harness.bridge.getStartCount());

        assertThrows(IllegalStateException.class, encoder::start);

        assertEquals(1, harness.bridge.getStartCount());
        assertEquals(1, harness.bridge.getConfigureCount());
        assertEquals(1, harness.bridge.getCreateInputSurfaceCount());
        encoder.close();
        assertEquals(1, harness.bridge.getReleaseCount());
    }

    @Test
    public void start_afterClose_throwsIllegalStateException() {
        Harness harness = new Harness();
        H264Encoder encoder = harness.newEncoder();
        encoder.close();
        assertEquals(1, harness.bridge.getReleaseCount());
        assertTrue(encoder.isBridgeReleased());

        assertThrows(IllegalStateException.class, encoder::start);

        assertEquals(0, harness.bridge.getStartCount());
        assertEquals(1, harness.bridge.getReleaseCount());
        assertTrue(encoder.isBridgeReleased());
    }

    @Test
    public void formatChange_emitsConfigBeforeFirstAccessUnit() {
        Harness harness = new Harness();
        H264Encoder encoder = harness.newStartedEncoder();

        harness.bridge.enqueueFormatChange(H264Fixtures.sps(), H264Fixtures.pps(), WIDTH, HEIGHT);
        harness.bridge.enqueueSample(idrSample(), 33_000L, true, false);

        assertNotNull(harness.output.awaitAccessUnit(AWAIT_TIMEOUT_MS));
        assertEquals(Arrays.asList("config", "au"), harness.output.getEvents());

        EncoderConfig config = harness.output.awaitConfig(AWAIT_TIMEOUT_MS);
        assertNotNull(config);
        assertEquals(WIDTH, config.getCodedWidth());
        assertEquals(HEIGHT, config.getCodedHeight());
        assertEquals(2, config.getConfigParts().size());
        assertEquals("sps", config.getConfigParts().get(0).getId());
        assertArrayEquals(H264Fixtures.sps(), config.getConfigParts().get(0).getPayload());
        assertEquals("pps", config.getConfigParts().get(1).getId());
        assertArrayEquals(H264Fixtures.pps(), config.getConfigParts().get(1).getPayload());
        encoder.close();
    }

    @Test
    public void formatChangeWithoutCsd_waitsForInBandConfig() {
        Harness harness = new Harness();
        H264Encoder encoder = harness.newStartedEncoder();

        harness.bridge.enqueueFormatChange(null, null, WIDTH, HEIGHT);
        assertNull(harness.output.awaitConfig(QUIET_WINDOW_MS));

        harness.bridge.enqueueSample(H264Fixtures.inBandConfigAndIdr(), 5_000L, true, false);

        assertNotNull(harness.output.awaitConfig(AWAIT_TIMEOUT_MS));
        assertNotNull(harness.output.awaitAccessUnit(AWAIT_TIMEOUT_MS));
        assertEquals(0, harness.output.getFailureCount());
        encoder.close();
    }

    @Test
    public void invalidCodedSizeFromFormat_failsEncoder() {
        Harness harness = new Harness();
        H264Encoder encoder = harness.newStartedEncoder();

        harness.bridge.enqueueFormatChange(H264Fixtures.sps(), H264Fixtures.pps(), 0, 0);

        assertNotNull(harness.output.awaitFailure(AWAIT_TIMEOUT_MS));
        assertEquals(1, harness.output.getFailureCount());
        assertEquals(0, harness.output.getConfigCount());
        assertEquals(0, harness.output.getAccessUnitCount());

        harness.bridge.enqueueSample(H264Fixtures.inBandConfigAndIdr(), 1_000L, true, false);
        assertNull(harness.output.awaitAccessUnit(QUIET_WINDOW_MS));
        assertNull(harness.output.awaitFailure(QUIET_WINDOW_MS));
        assertEquals(0, harness.output.getConfigCount());
        encoder.close();
    }

    @Test
    public void codecConfigWithoutSpsPps_failsEncoder() {
        Harness harness = new Harness();
        H264Encoder encoder = harness.newStartedEncoder();

        harness.bridge.enqueueSample(pSample(), 0L, false, true);

        assertNotNull(harness.output.awaitFailure(AWAIT_TIMEOUT_MS));
        assertEquals(1, harness.output.getFailureCount());
        assertEquals(0, harness.output.getConfigCount());
        assertEquals(0, harness.output.getAccessUnitCount());
        encoder.close();
    }

    @Test
    public void inBandConfigPlusIdr_emitsConfigThenFirstIdr() {
        Harness harness = new Harness();
        H264Encoder encoder = harness.newStartedEncoder();

        // Strict config contract: the actual output format must be observed before any config.
        // The CSD is intentionally empty; the SPS/PPS arrive in-band with the IDR.
        harness.bridge.enqueueFormatChange(null, null, WIDTH, HEIGHT);
        harness.bridge.enqueueSample(H264Fixtures.inBandConfigAndIdr(), 7_000L, true, false);

        EncoderConfig config = harness.output.awaitConfig(AWAIT_TIMEOUT_MS);
        assertNotNull(config);
        assertTrue(config.getCodedWidth() > 0);
        assertTrue(config.getCodedHeight() > 0);

        EncodedAccessUnit accessUnit = harness.output.awaitAccessUnit(AWAIT_TIMEOUT_MS);
        assertNotNull(accessUnit);
        assertEquals(Arrays.asList("config", "au"), harness.output.getEvents());
        assertEquals(0L, accessUnit.getSequence());
        assertEquals(7_000L, accessUnit.getPtsUs());
        assertTrue(accessUnit.getFlags().isJoinPoint());
        assertEquals(0, harness.output.getFailureCount());
        encoder.close();
    }

    @Test
    public void configChangeAfterPublish_failsEncoder() {
        Harness harness = new Harness();
        H264Encoder encoder = harness.newStartedEncoder();

        harness.bridge.enqueueFormatChange(H264Fixtures.sps(), H264Fixtures.pps(), WIDTH, HEIGHT);
        assertNotNull(harness.output.awaitConfig(AWAIT_TIMEOUT_MS));
        harness.bridge.enqueueSample(idrSample(), 1_000L, true, false);
        assertNotNull(harness.output.awaitAccessUnit(AWAIT_TIMEOUT_MS));

        harness.bridge.enqueueSample(changedConfigIdrSample(), 2_000L, true, false);

        assertNotNull(harness.output.awaitFailure(AWAIT_TIMEOUT_MS));
        assertEquals(1, harness.output.getFailureCount());
        assertEquals(1, harness.output.getAccessUnitCount());
        encoder.close();
    }

    @Test
    public void differentConfigBeforeFirstAu_failsEncoder() {
        Harness harness = new Harness();
        H264Encoder encoder = harness.newStartedEncoder();

        harness.bridge.enqueueFormatChange(H264Fixtures.sps(), H264Fixtures.pps(), WIDTH, HEIGHT);
        assertNotNull(harness.output.awaitConfig(AWAIT_TIMEOUT_MS));

        harness.bridge.enqueueSample(changedConfigIdrSample(), 2_000L, true, false);

        assertNotNull(harness.output.awaitFailure(AWAIT_TIMEOUT_MS));
        assertEquals(1, harness.output.getFailureCount());
        assertEquals(1, harness.output.getConfigCount());
        assertEquals(0, harness.output.getAccessUnitCount());
        encoder.close();
    }

    @Test
    public void duplicateIdenticalConfig_isIgnored() {
        Harness harness = new Harness();
        H264Encoder encoder = harness.newStartedEncoder();

        harness.bridge.enqueueFormatChange(H264Fixtures.sps(), H264Fixtures.pps(), WIDTH, HEIGHT);
        assertNotNull(harness.output.awaitConfig(AWAIT_TIMEOUT_MS));

        harness.bridge.enqueueSample(H264Fixtures.inBandConfigAndIdr(), 2_000L, true, false);

        EncodedAccessUnit accessUnit = harness.output.awaitAccessUnit(AWAIT_TIMEOUT_MS);
        assertNotNull(accessUnit);
        assertEquals(0L, accessUnit.getSequence());
        assertEquals(1, harness.output.getConfigCount());
        assertEquals(0, harness.output.getFailureCount());
        encoder.close();
    }

    @Test
    public void preIdrSamples_areDroppedUntilFirstIdr() {
        Harness harness = new Harness();
        H264Encoder encoder = harness.newStartedEncoder();

        harness.bridge.enqueueFormatChange(H264Fixtures.sps(), H264Fixtures.pps(), WIDTH, HEIGHT);
        assertNotNull(harness.output.awaitConfig(AWAIT_TIMEOUT_MS));

        harness.bridge.enqueueSample(pSample(), 1_000L, false, false);
        harness.bridge.enqueueSample(pSample(), 2_000L, false, false);
        harness.bridge.enqueueSample(idrSample(), 3_000L, true, false);
        harness.bridge.enqueueSample(pSample(), 4_000L, false, false);

        EncodedAccessUnit first = harness.output.awaitAccessUnit(AWAIT_TIMEOUT_MS);
        assertNotNull(first);
        assertEquals(0L, first.getSequence());
        assertEquals(3_000L, first.getPtsUs());
        assertTrue(first.getFlags().isJoinPoint());

        EncodedAccessUnit second = harness.output.awaitAccessUnit(AWAIT_TIMEOUT_MS);
        assertNotNull(second);
        assertEquals(1L, second.getSequence());
        assertEquals(4_000L, second.getPtsUs());
        assertFalse(second.getFlags().isJoinPoint());
        assertNull(harness.output.awaitAccessUnit(QUIET_WINDOW_MS));
        assertEquals(2, harness.output.getAccessUnitCount());
        assertEquals(0, harness.output.getFailureCount());
        encoder.close();
    }

    @Test
    public void firstIdr_hasEpochSequenceZeroPtsDtsAndJoinInfo() {
        Harness harness = new Harness();
        H264Encoder encoder = harness.newStartedEncoder();

        harness.bridge.enqueueFormatChange(H264Fixtures.sps(), H264Fixtures.pps(), WIDTH, HEIGHT);
        assertNotNull(harness.output.awaitConfig(AWAIT_TIMEOUT_MS));

        harness.bridge.enqueueSample(H264Fixtures.inBandConfigAndIdr(), 33_000L, false, false);

        EncodedAccessUnit accessUnit = harness.output.awaitAccessUnit(AWAIT_TIMEOUT_MS);
        assertNotNull(accessUnit);
        assertEquals(STREAM_KEY, accessUnit.getStreamKey());
        assertEquals(CONFIG_EPOCH, accessUnit.getConfigEpoch());
        assertEquals(0L, accessUnit.getSequence());
        assertEquals(33_000L, accessUnit.getPtsUs());
        assertEquals(33_000L, accessUnit.getDtsUs());
        assertTrue(accessUnit.getFlags().isJoinPoint());
        assertTrue(accessUnit.getFlags().isSyncPoint());
        assertFalse(accessUnit.getFlags().isConfig());

        assertNotNull(accessUnit.getJoinInfo());
        assertEquals(CONFIG_EPOCH, accessUnit.getJoinInfo().getConfigEpoch());
        assertEquals(2, accessUnit.getJoinInfo().getRequiredConfigParts().size());
        assertEquals("sps", accessUnit.getJoinInfo().getRequiredConfigParts().get(0).getId());
        assertArrayEquals(H264Fixtures.sps(),
                accessUnit.getJoinInfo().getRequiredConfigParts().get(0).getPayload());
        assertEquals("pps", accessUnit.getJoinInfo().getRequiredConfigParts().get(1).getId());
        assertArrayEquals(idrSample(), accessUnit.getPayload());
        encoder.close();
    }

    @Test
    public void keyFrameFlagWithNonIdrPayload_isNotJoinPoint() {
        Harness harness = new Harness();
        H264Encoder encoder = harness.newStartedEncoder();

        harness.bridge.enqueueFormatChange(H264Fixtures.sps(), H264Fixtures.pps(), WIDTH, HEIGHT);
        assertNotNull(harness.output.awaitConfig(AWAIT_TIMEOUT_MS));
        harness.bridge.enqueueSample(idrSample(), 5_000L, true, false);
        assertNotNull(harness.output.awaitAccessUnit(AWAIT_TIMEOUT_MS));

        harness.bridge.enqueueSample(pSample(), 6_000L, true, false);

        EncodedAccessUnit accessUnit = harness.output.awaitAccessUnit(AWAIT_TIMEOUT_MS);
        assertNotNull(accessUnit);
        assertEquals(1L, accessUnit.getSequence());
        assertFalse(accessUnit.getFlags().isJoinPoint());
        assertFalse(accessUnit.getFlags().isSyncPoint());
        encoder.close();
    }

    @Test
    public void idrWithMissingKeyFrameFlag_isStillJoinPoint() {
        Harness harness = new Harness();
        H264Encoder encoder = harness.newStartedEncoder();

        harness.bridge.enqueueFormatChange(H264Fixtures.sps(), H264Fixtures.pps(), WIDTH, HEIGHT);
        assertNotNull(harness.output.awaitConfig(AWAIT_TIMEOUT_MS));

        harness.bridge.enqueueSample(idrSample(), 9_000L, false, false);

        EncodedAccessUnit accessUnit = harness.output.awaitAccessUnit(AWAIT_TIMEOUT_MS);
        assertNotNull(accessUnit);
        assertEquals(0L, accessUnit.getSequence());
        assertTrue(accessUnit.getFlags().isJoinPoint());
        assertTrue(accessUnit.getFlags().isSyncPoint());
        encoder.close();
    }

    @Test
    public void inBandSpsPps_areStrippedAndBecomeConfig() {
        Harness harness = new Harness();
        H264Encoder encoder = harness.newStartedEncoder();

        // Strict config contract: the actual output format is required even though the CSD is empty.
        harness.bridge.enqueueFormatChange(null, null, WIDTH, HEIGHT);
        harness.bridge.enqueueSample(H264Fixtures.inBandConfigAndIdr(), 1_000L, true, false);

        EncoderConfig config = harness.output.awaitConfig(AWAIT_TIMEOUT_MS);
        assertNotNull(config);
        assertEquals(2, config.getConfigParts().size());
        assertEquals("sps", config.getConfigParts().get(0).getId());
        assertArrayEquals(H264Fixtures.sps(), config.getConfigParts().get(0).getPayload());
        assertEquals("pps", config.getConfigParts().get(1).getId());
        assertArrayEquals(H264Fixtures.pps(), config.getConfigParts().get(1).getPayload());

        EncodedAccessUnit accessUnit = harness.output.awaitAccessUnit(AWAIT_TIMEOUT_MS);
        assertNotNull(accessUnit);
        assertArrayEquals(idrSample(), accessUnit.getPayload());
        assertEquals(Collections.singletonList(5), nalTypes(accessUnit.getPayload()));
        encoder.close();
    }

    @Test
    public void codecConfigBuffer_isNotEmittedAsDisplayAu() {
        Harness harness = new Harness();
        H264Encoder encoder = harness.newStartedEncoder();

        // Strict config contract: an actual output format must precede the codec-config buffer.
        harness.bridge.enqueueFormatChange(null, null, WIDTH, HEIGHT);
        harness.bridge.enqueueSample(
                H264Fixtures.annexB(H264Fixtures.sps(), H264Fixtures.pps()), 0L, false, true);

        assertNotNull(harness.output.awaitConfig(AWAIT_TIMEOUT_MS));
        assertNull(harness.output.awaitAccessUnit(QUIET_WINDOW_MS));
        assertEquals(0, harness.output.getAccessUnitCount());

        harness.bridge.enqueueSample(idrSample(), 1_000L, true, false);

        assertNotNull(harness.output.awaitAccessUnit(AWAIT_TIMEOUT_MS));
        assertEquals(1, harness.output.getConfigCount());
        assertEquals(0, harness.output.getFailureCount());
        encoder.close();
    }

    @Test
    public void configOnlySample_doesNotProduceAu() {
        Harness harness = new Harness();
        H264Encoder encoder = harness.newStartedEncoder();

        // Strict config contract: an actual output format must precede the codec-config buffer.
        harness.bridge.enqueueFormatChange(null, null, WIDTH, HEIGHT);
        harness.bridge.enqueueSample(
                H264Fixtures.annexB(H264Fixtures.sps(), H264Fixtures.pps()), 0L, false, true);

        assertNotNull(harness.output.awaitConfig(AWAIT_TIMEOUT_MS));
        assertNull(harness.output.awaitAccessUnit(QUIET_WINDOW_MS));
        assertEquals(0, harness.output.getAccessUnitCount());
        assertEquals(0, harness.output.getFailureCount());
        encoder.close();
    }

    @Test
    public void bSlice_failsEncoderEvenIfLaterVcl() {
        Harness harness = new Harness();
        H264Encoder encoder = harness.newStartedEncoder();

        harness.bridge.enqueueSample(
                H264Fixtures.annexB(H264Fixtures.sps(), H264Fixtures.pps(),
                        H264Fixtures.idrSlice(), H264Fixtures.bSlice()),
                1_000L, true, false);

        assertNotNull(harness.output.awaitFailure(AWAIT_TIMEOUT_MS));
        assertEquals(1, harness.output.getFailureCount());
        assertEquals(0, harness.output.getAccessUnitCount());
        encoder.close();
    }

    @Test
    public void auBeforeConfig_failsOnceAndStops() {
        Harness harness = new Harness();
        H264Encoder encoder = harness.newStartedEncoder();

        harness.bridge.enqueueSample(pSample(), 1_000L, false, false);

        assertNotNull(harness.output.awaitFailure(AWAIT_TIMEOUT_MS));
        assertEquals(1, harness.output.getFailureCount());
        assertEquals(0, harness.output.getConfigCount());
        assertEquals(0, harness.output.getAccessUnitCount());

        harness.bridge.enqueueSample(H264Fixtures.inBandConfigAndIdr(), 2_000L, true, false);
        assertNull(harness.output.awaitAccessUnit(QUIET_WINDOW_MS));
        assertEquals(1, harness.output.getFailureCount());
        encoder.close();
    }

    @Test
    public void bridgeRuntimeException_failsOnceAndStopsThread() {
        Harness harness = new Harness();
        H264Encoder encoder = harness.newStartedEncoder();
        harness.bridge.failNextDequeue(new RuntimeException("dequeue failed"));

        assertNotNull(harness.output.awaitFailure(AWAIT_TIMEOUT_MS));
        assertEquals(1, harness.output.getFailureCount());

        harness.bridge.enqueueSample(H264Fixtures.inBandConfigAndIdr(), 3_000L, true, false);
        assertNull(harness.output.awaitAccessUnit(QUIET_WINDOW_MS));
        assertEquals(1, harness.output.getFailureCount());
        encoder.close();
    }

    @Test
    public void outputListenerException_failsOnce() {
        Harness harness = new Harness();
        harness.output.failNextAccessUnit(new RuntimeException("listener failed"));
        H264Encoder encoder = harness.newStartedEncoder();

        harness.bridge.enqueueFormatChange(H264Fixtures.sps(), H264Fixtures.pps(), WIDTH, HEIGHT);
        harness.bridge.enqueueSample(idrSample(), 1_000L, true, false);

        assertNotNull(harness.output.awaitFailure(AWAIT_TIMEOUT_MS));
        assertEquals(1, harness.output.getFailureCount());
        assertEquals(0, harness.output.getAccessUnitCount());

        harness.bridge.enqueueSample(idrSample(), 2_000L, true, false);
        assertNull(harness.output.awaitAccessUnit(QUIET_WINDOW_MS));
        assertEquals(1, harness.output.getFailureCount());
        encoder.close();
    }

    @Test
    public void stop_joinsDrainThreadAndStopsBridgeOnce() {
        Harness harness = new Harness();
        H264Encoder encoder = harness.newStartedEncoder();
        harness.bridge.enqueueFormatChange(H264Fixtures.sps(), H264Fixtures.pps(), WIDTH, HEIGHT);
        harness.bridge.enqueueSample(idrSample(), 1_000L, true, false);
        assertNotNull(harness.output.awaitAccessUnit(AWAIT_TIMEOUT_MS));

        encoder.stop();

        assertEquals(1, harness.bridge.getStopCount());
        harness.bridge.enqueueSample(idrSample(), 2_000L, true, false);
        assertNull(harness.output.awaitAccessUnit(QUIET_WINDOW_MS));
        encoder.close();
        assertEquals(1, harness.bridge.getReleaseCount());
    }

    @Test
    public void stop_isIdempotent() {
        Harness idleHarness = new Harness();
        H264Encoder notStarted = idleHarness.newEncoder();

        notStarted.stop();

        assertEquals(0, idleHarness.bridge.getStopCount());
        assertEquals(0, idleHarness.bridge.getReleaseCount());
        notStarted.close();

        Harness harness = new Harness();
        H264Encoder encoder = harness.newStartedEncoder();

        encoder.stop();
        encoder.stop();

        assertEquals(1, harness.bridge.getStopCount());
        encoder.close();
    }

    @Test
    public void stop_joinTimeout_throwsEncoderException() {
        Harness harness = new Harness();
        harness.output.blockNextConfigCallback();
        H264Encoder encoder = harness.newStartedEncoder();
        harness.bridge.enqueueFormatChange(H264Fixtures.sps(), H264Fixtures.pps(), WIDTH, HEIGHT);
        assertTrue(harness.output.awaitConfigCallbackEntered(AWAIT_TIMEOUT_MS));

        try {
            assertThrows(EncoderException.class, encoder::stop);
            assertEquals(0, harness.bridge.getStopCount());
        } finally {
            harness.output.releaseBlockedConfigCallback();
            encoder.close();
        }

        assertEquals(1, harness.bridge.getStopCount());
        assertEquals(1, harness.bridge.getReleaseCount());
        assertTrue(encoder.isBridgeReleased());
        assertFalse(encoder.isLeakDetected());
    }

    @Test
    public void stop_bridgeStopExceptionIsSwallowed() {
        FakeMediaCodecEncoderBridge fake = new FakeMediaCodecEncoderBridge();
        StopFailingBridge bridge = new StopFailingBridge(fake);
        RecordingOutput output = new RecordingOutput();
        H264Encoder encoder = new H264Encoder(STREAM_KEY, descriptor(), PARAMETERS, output,
                new FixedBridgeFactory(bridge));
        encoder.start();

        encoder.stop();

        assertEquals(1, bridge.getStopAttempts());
        assertEquals(0, fake.getStopCount());
        assertEquals(0, output.getFailureCount());
        encoder.close();
        assertEquals(1, fake.getReleaseCount());
        assertTrue(encoder.isBridgeReleased());
        assertNull(encoder.getInputSurface());
    }

    @Test
    public void close_waitsForDrainTerminationBeforeRelease() {
        Harness harness = new Harness();
        H264Encoder encoder = harness.newStartedEncoder();
        harness.bridge.enqueueFormatChange(H264Fixtures.sps(), H264Fixtures.pps(), WIDTH, HEIGHT);
        harness.bridge.enqueueSample(idrSample(), 1_000L, true, false);
        assertNotNull(harness.output.awaitAccessUnit(AWAIT_TIMEOUT_MS));

        encoder.close();

        assertEquals(1, harness.bridge.getReleaseCount());
        assertTrue(encoder.isBridgeReleased());
        assertFalse(encoder.isLeakDetected());
        assertNull(encoder.getInputSurface());
        harness.bridge.enqueueSample(idrSample(), 2_000L, true, false);
        assertNull(harness.output.awaitAccessUnit(QUIET_WINDOW_MS));
    }

    @Test
    public void close_doesNotReleaseBridgeWhenDrainDoesNotTerminate() {
        Harness harness = new Harness();
        harness.output.blockNextConfigCallback();
        H264Encoder encoder = harness.newStartedEncoder();

        harness.bridge.enqueueFormatChange(H264Fixtures.sps(), H264Fixtures.pps(), WIDTH, HEIGHT);
        assertTrue(harness.output.awaitConfigCallbackEntered(AWAIT_TIMEOUT_MS));

        encoder.close();

        assertTrue(encoder.isLeakDetected());
        assertEquals(0, harness.bridge.getReleaseCount());
        assertFalse(encoder.isBridgeReleased());

        harness.output.releaseBlockedConfigCallback();
        encoder.close();

        assertEquals(1, harness.bridge.getReleaseCount());
        assertTrue(encoder.isBridgeReleased());

        encoder.close();
        assertEquals(1, harness.bridge.getReleaseCount());
    }

    @Test
    public void close_isNoThrowAndIdempotent() {
        Harness harness = new Harness();
        H264Encoder encoder = harness.newEncoder();

        encoder.close();
        encoder.close();
        encoder.close();

        assertEquals(1, harness.bridge.getReleaseCount());
        assertTrue(encoder.isBridgeReleased());
        assertFalse(encoder.isLeakDetected());
        assertNull(encoder.getInputSurface());
    }

    @Test
    public void lateSampleAfterClose_isNotEmitted() {
        Harness harness = new Harness();
        H264Encoder encoder = harness.newStartedEncoder();
        harness.bridge.enqueueFormatChange(H264Fixtures.sps(), H264Fixtures.pps(), WIDTH, HEIGHT);
        harness.bridge.enqueueSample(idrSample(), 1_000L, true, false);
        assertNotNull(harness.output.awaitAccessUnit(AWAIT_TIMEOUT_MS));

        encoder.close();
        harness.bridge.enqueueSample(H264Fixtures.inBandConfigAndIdr(), 99_000L, true, false);

        assertNull(harness.output.awaitAccessUnit(QUIET_WINDOW_MS));
        assertEquals(1, harness.output.getAccessUnitCount());
        assertEquals(0, harness.output.getFailureCount());
    }

    @Test
    public void requestSyncPoint_forwardsToBridge() {
        Harness harness = new Harness();
        H264Encoder encoder = harness.newStartedEncoder();
        assertEquals(0, harness.bridge.getRequestSyncFrameCount());

        encoder.requestSyncPoint();

        assertEquals(1, harness.bridge.getRequestSyncFrameCount());
        encoder.close();
    }

    @Test
    public void requestSyncPointBridgeFailure_isEncoderException() {
        FakeMediaCodecEncoderBridge fake = new FakeMediaCodecEncoderBridge();
        RequestSyncFailingBridge bridge = new RequestSyncFailingBridge(fake);
        RecordingOutput output = new RecordingOutput();
        H264Encoder encoder = new H264Encoder(STREAM_KEY, descriptor(), PARAMETERS, output,
                new FixedBridgeFactory(bridge));
        encoder.start();
        bridge.failNextRequestSyncFrame(new RuntimeException("sync failed"));

        assertThrows(EncoderException.class, encoder::requestSyncPoint);

        assertEquals(0, fake.getRequestSyncFrameCount());
        encoder.close();
    }

    @Test
    public void updateParameters_appliesBitrateOnly() {
        Harness harness = new Harness();
        H264Encoder encoder = harness.newStartedEncoder();

        encoder.updateParameters(new StreamParameters(1_800_000, 90, 2000));

        assertEquals(Collections.singletonList(1_800_000), harness.bridge.getBitrateUpdates());
        assertEquals(1, harness.bridge.getConfigureCount());
        assertEquals(1, harness.bridge.getCreateInputSurfaceCount());
        assertEquals(1, harness.bridge.getStartCount());
        assertEquals(0, harness.bridge.getRequestSyncFrameCount());

        encoder.updateParameters(new StreamParameters(1_800_000, 15, 500));
        assertEquals(1, harness.bridge.getBitrateUpdates().size());

        encoder.updateParameters(new StreamParameters(0, 15, 500));
        assertEquals(Arrays.asList(1_800_000, 1), harness.bridge.getBitrateUpdates());
        encoder.close();
    }

    @Test
    public void negativePts_failsEncoder() {
        Harness harness = new Harness();
        H264Encoder encoder = harness.newStartedEncoder();
        harness.bridge.enqueueFormatChange(H264Fixtures.sps(), H264Fixtures.pps(), WIDTH, HEIGHT);
        assertNotNull(harness.output.awaitConfig(AWAIT_TIMEOUT_MS));

        harness.bridge.enqueueSample(idrSample(), -1_000L, true, false);

        assertNotNull(harness.output.awaitFailure(AWAIT_TIMEOUT_MS));
        assertEquals(1, harness.output.getFailureCount());
        assertEquals(0, harness.output.getAccessUnitCount());
        encoder.close();
    }

    @Test
    public void factory_createReturnsConfiguredEncoderWithoutStartingIt() {
        Harness harness = new Harness();
        H264EncoderFactory encoderFactory = new H264EncoderFactory(harness.factory);

        Encoder encoder = encoderFactory.create(STREAM_KEY, descriptor(), PARAMETERS,
                harness.output);

        assertTrue(encoder instanceof H264Encoder);
        assertEquals(1, harness.bridge.getConfigureCount());
        assertEquals(1, harness.bridge.getCreateInputSurfaceCount());
        assertEquals(0, harness.bridge.getStartCount());
        assertEquals(Collections.singletonList(H264_MIME), harness.factory.getEncoderMimes());
        encoder.close();
    }

    // ---- I1: a codec-config sample that also carries VCL must not lose its first IDR. ----

    @Test
    public void codecConfigWithVcl_emitsConfigThenFirstIdr() {
        Harness harness = new Harness();
        H264Encoder encoder = harness.newStartedEncoder();

        // Incomplete CSD: the format change cannot publish a config on its own.
        harness.bridge.enqueueFormatChange(new byte[0], new byte[0], WIDTH, HEIGHT);
        assertNull(harness.output.awaitConfig(QUIET_WINDOW_MS));

        harness.bridge.enqueueSample(H264Fixtures.inBandConfigAndIdr(), 5_000L, true, true);

        EncoderConfig config = harness.output.awaitConfig(AWAIT_TIMEOUT_MS);
        assertNotNull("the shared config+VCL packet must publish SPS/PPS", config);
        assertEquals(2, config.getConfigParts().size());

        EncodedAccessUnit accessUnit = harness.output.awaitAccessUnit(AWAIT_TIMEOUT_MS);
        assertNotNull("the IDR sharing the codec-config packet must be emitted", accessUnit);
        assertEquals(0L, accessUnit.getSequence());
        assertEquals(5_000L, accessUnit.getPtsUs());
        assertTrue(accessUnit.getFlags().isJoinPoint());
        assertTrue(accessUnit.getFlags().isSyncPoint());
        assertArrayEquals("in-band SPS/PPS must be stripped from the emitted IDR",
                idrSample(), accessUnit.getPayload());
        assertEquals(Collections.singletonList(5), nalTypes(accessUnit.getPayload()));
        assertEquals(1, harness.output.getConfigCount());
        assertEquals(1, harness.output.getAccessUnitCount());
        assertEquals(0, harness.output.getFailureCount());
        encoder.close();
    }

    @Test
    public void codecConfigWithVclWhenConfigAlreadyPublished_doesNotRepublishConfig() {
        Harness harness = new Harness();
        H264Encoder encoder = harness.newStartedEncoder();

        harness.bridge.enqueueFormatChange(H264Fixtures.sps(), H264Fixtures.pps(), WIDTH, HEIGHT);
        assertNotNull(harness.output.awaitConfig(AWAIT_TIMEOUT_MS));

        harness.bridge.enqueueSample(H264Fixtures.inBandConfigAndIdr(), 6_000L, true, true);

        EncodedAccessUnit accessUnit = harness.output.awaitAccessUnit(AWAIT_TIMEOUT_MS);
        assertNotNull("the IDR in the codec-config packet must be emitted", accessUnit);
        assertEquals(0L, accessUnit.getSequence());
        assertEquals(6_000L, accessUnit.getPtsUs());
        assertTrue(accessUnit.getFlags().isJoinPoint());
        assertArrayEquals(idrSample(), accessUnit.getPayload());
        assertEquals("an identical in-band config must not be re-published", 1,
                harness.output.getConfigCount());
        assertEquals(0, harness.output.getFailureCount());
        encoder.close();
    }

    @Test
    public void codecConfigWithNonIdrVclBeforeFirstIdr_emitsNoAu() {
        Harness harness = new Harness();
        H264Encoder encoder = harness.newStartedEncoder();

        harness.bridge.enqueueFormatChange(new byte[0], new byte[0], WIDTH, HEIGHT);
        harness.bridge.enqueueSample(
                H264Fixtures.annexB(H264Fixtures.sps(), H264Fixtures.pps(),
                        H264Fixtures.pSlice()),
                1_000L, false, true);

        assertNotNull(harness.output.awaitConfig(AWAIT_TIMEOUT_MS));
        assertNull("a non-IDR VCL must stay dropped until the first IDR",
                harness.output.awaitAccessUnit(QUIET_WINDOW_MS));
        assertEquals(0, harness.output.getAccessUnitCount());
        assertEquals(0, harness.output.getFailureCount());
        encoder.close();
    }

    @Test
    public void codecConfigWithChangedConfigAndVclAfterPublish_failsWithoutAu() {
        Harness harness = new Harness();
        H264Encoder encoder = harness.newStartedEncoder();

        harness.bridge.enqueueFormatChange(H264Fixtures.sps(), H264Fixtures.pps(), WIDTH, HEIGHT);
        assertNotNull(harness.output.awaitConfig(AWAIT_TIMEOUT_MS));
        harness.bridge.enqueueSample(idrSample(), 1_000L, true, false);
        assertNotNull(harness.output.awaitAccessUnit(AWAIT_TIMEOUT_MS));

        harness.bridge.enqueueSample(changedConfigIdrSample(), 2_000L, true, true);

        EncoderException failure = harness.output.awaitFailure(AWAIT_TIMEOUT_MS);
        assertNotNull(failure);
        assertEquals("encoder config changed without a new epoch", failure.getMessage());
        assertEquals(1, harness.output.getConfigCount());
        assertEquals(1, harness.output.getAccessUnitCount());
        encoder.close();
    }

    // ---- I2: requested size/color must never stand in for the actual output format. ----

    @Test
    public void requestedSizeDiffersFromActual_emitsActualCodedSize() {
        Harness harness = new Harness();
        H264Encoder encoder = harness.newStartedEncoder();

        harness.bridge.enqueueFormatChange(H264Fixtures.sps(), H264Fixtures.pps(), 1280, 720);

        EncoderConfig config = harness.output.awaitConfig(AWAIT_TIMEOUT_MS);
        assertNotNull(config);
        assertEquals("the requested 640x480 must not replace the actual coded width",
                1280, config.getCodedWidth());
        assertEquals("the requested 640x480 must not replace the actual coded height",
                720, config.getCodedHeight());
        encoder.close();
    }

    @Test
    public void formatChangeWithoutCsd_laterInBandConfigUsesActualSizeAndColor() {
        FormatHarness harness = new FormatHarness();
        harness.encoder.start();

        harness.enqueueFormatWithoutCsd(1280, 720, BT709);
        assertNull(harness.output.awaitConfig(QUIET_WINDOW_MS));

        harness.fake.enqueueSample(H264Fixtures.inBandConfigAndIdr(), 7_000L, true, false);

        EncoderConfig config = harness.output.awaitConfig(AWAIT_TIMEOUT_MS);
        assertNotNull(config);
        assertEquals(1280, config.getCodedWidth());
        assertEquals(720, config.getCodedHeight());
        assertColor("the actual format color must win over the requested color",
                BT709, config.getColorInfo());
        EncodedAccessUnit accessUnit = harness.output.awaitAccessUnit(AWAIT_TIMEOUT_MS);
        assertNotNull(accessUnit);
        assertEquals(0L, accessUnit.getSequence());
        assertEquals(0, harness.output.getFailureCount());
        harness.encoder.close();
    }

    @Test
    public void codecConfigBeforeOutputFormat_failsWithoutConfigOrAu() {
        Harness harness = new Harness();
        H264Encoder encoder = harness.newStartedEncoder();

        harness.bridge.enqueueSample(H264Fixtures.inBandConfigAndIdr(), 1_000L, true, true);

        EncoderException failure = harness.output.awaitFailure(AWAIT_TIMEOUT_MS);
        assertNotNull("a config without any observed output format must fail", failure);
        assertEquals(1, harness.output.getFailureCount());
        assertEquals(0, harness.output.getConfigCount());
        assertEquals(0, harness.output.getAccessUnitCount());

        harness.bridge.enqueueSample(H264Fixtures.inBandConfigAndIdr(), 2_000L, true, true);
        assertNull(harness.output.awaitConfig(QUIET_WINDOW_MS));
        assertNull(harness.output.awaitAccessUnit(QUIET_WINDOW_MS));
        assertEquals(1, harness.output.getFailureCount());
        encoder.close();
    }

    @Test
    public void inBandConfigBeforeOutputFormat_failsWithoutConfigOrAu() {
        Harness harness = new Harness();
        H264Encoder encoder = harness.newStartedEncoder();

        harness.bridge.enqueueSample(H264Fixtures.inBandConfigAndIdr(), 1_000L, true, false);

        EncoderException failure = harness.output.awaitFailure(AWAIT_TIMEOUT_MS);
        assertNotNull("in-band config without an output format must fail", failure);
        assertEquals(1, harness.output.getFailureCount());
        assertEquals(0, harness.output.getConfigCount());
        assertEquals(0, harness.output.getAccessUnitCount());
        encoder.close();
    }

    @Test
    public void configSizeChangeBeforeFirstAu_failsEncoder() {
        Harness harness = new Harness();
        H264Encoder encoder = harness.newStartedEncoder();

        harness.bridge.enqueueFormatChange(H264Fixtures.sps(), H264Fixtures.pps(), 1280, 720);
        assertNotNull(harness.output.awaitConfig(AWAIT_TIMEOUT_MS));

        harness.bridge.enqueueFormatChange(H264Fixtures.sps(), H264Fixtures.pps(), 1920, 1080);

        EncoderException failure = harness.output.awaitFailure(AWAIT_TIMEOUT_MS);
        assertNotNull("a coded-size change with identical SPS/PPS must fail", failure);
        assertEquals("encoder config changed before first access unit", failure.getMessage());
        assertEquals(1, harness.output.getConfigCount());
        assertEquals(0, harness.output.getAccessUnitCount());
        encoder.close();
    }

    @Test
    public void configSizeChangeAfterPublish_failsEncoder() {
        Harness harness = new Harness();
        H264Encoder encoder = harness.newStartedEncoder();

        harness.bridge.enqueueFormatChange(H264Fixtures.sps(), H264Fixtures.pps(), 1280, 720);
        assertNotNull(harness.output.awaitConfig(AWAIT_TIMEOUT_MS));
        harness.bridge.enqueueSample(idrSample(), 1_000L, true, false);
        EncodedAccessUnit first = harness.output.awaitAccessUnit(AWAIT_TIMEOUT_MS);
        assertNotNull(first);
        assertEquals(0L, first.getSequence());

        harness.bridge.enqueueFormatChange(H264Fixtures.sps(), H264Fixtures.pps(), 1920, 1080);

        EncoderException failure = harness.output.awaitFailure(AWAIT_TIMEOUT_MS);
        assertNotNull("a coded-size change after publish must fail", failure);
        assertEquals("encoder config changed without a new epoch", failure.getMessage());
        assertEquals(1, harness.output.getConfigCount());
        assertEquals(1, harness.output.getAccessUnitCount());
        encoder.close();
    }

    @Test
    public void configColorChangeBeforeFirstAu_failsEncoder() {
        FormatHarness harness = new FormatHarness();
        harness.encoder.start();

        harness.enqueueFormat(WIDTH, HEIGHT, BT709);
        EncoderConfig config = harness.output.awaitConfig(AWAIT_TIMEOUT_MS);
        assertNotNull(config);
        assertColor("the published config must carry the actual color",
                BT709, config.getColorInfo());

        harness.enqueueFormat(WIDTH, HEIGHT, BT2020);

        EncoderException failure = harness.output.awaitFailure(AWAIT_TIMEOUT_MS);
        assertNotNull("a color change with identical SPS/PPS and size must fail", failure);
        assertEquals("encoder config changed before first access unit", failure.getMessage());
        assertEquals(1, harness.output.getConfigCount());
        assertEquals(0, harness.output.getAccessUnitCount());
        harness.encoder.close();
    }

    @Test
    public void configColorChangeAfterPublish_failsEncoder() {
        FormatHarness harness = new FormatHarness();
        harness.encoder.start();

        harness.enqueueFormat(WIDTH, HEIGHT, BT709);
        assertNotNull(harness.output.awaitConfig(AWAIT_TIMEOUT_MS));
        harness.fake.enqueueSample(idrSample(), 1_000L, true, false);
        assertNotNull(harness.output.awaitAccessUnit(AWAIT_TIMEOUT_MS));

        harness.enqueueFormat(WIDTH, HEIGHT, BT2020);

        EncoderException failure = harness.output.awaitFailure(AWAIT_TIMEOUT_MS);
        assertNotNull("a color change after publish must fail", failure);
        assertEquals("encoder config changed without a new epoch", failure.getMessage());
        assertEquals(1, harness.output.getConfigCount());
        assertEquals(1, harness.output.getAccessUnitCount());
        harness.encoder.close();
    }

    @Test
    public void incompleteCsdSizeChangeBeforeFirstAu_failsOnNextAu() {
        FormatHarness harness = new FormatHarness();
        harness.encoder.start();

        harness.enqueueFormat(1280, 720, BT709);
        assertNotNull(harness.output.awaitConfig(AWAIT_TIMEOUT_MS));

        // An empty-CSD format change still reports the actual size; the published config must not
        // silently keep applying to later access units.
        harness.enqueueFormatWithoutCsd(1920, 1080, BT709);
        harness.fake.enqueueSample(idrSample(), 1_000L, true, false);

        EncoderException failure = harness.output.awaitFailure(AWAIT_TIMEOUT_MS);
        assertNotNull("an empty-CSD format size change must fail instead of reusing the old config"
                        + evidence(harness.output),
                failure);
        assertEquals(1, harness.output.getConfigCount());
        assertEquals(0, harness.output.getAccessUnitCount());
        harness.encoder.close();
    }

    @Test
    public void incompleteCsdSizeChangeAfterPublish_failsOnNextAu() {
        FormatHarness harness = new FormatHarness();
        harness.encoder.start();

        harness.enqueueFormat(1280, 720, BT709);
        assertNotNull(harness.output.awaitConfig(AWAIT_TIMEOUT_MS));
        harness.fake.enqueueSample(idrSample(), 1_000L, true, false);
        assertNotNull(harness.output.awaitAccessUnit(AWAIT_TIMEOUT_MS));

        harness.enqueueFormatWithoutCsd(1920, 1080, BT709);
        harness.fake.enqueueSample(pSample(), 2_000L, false, false);

        EncoderException failure = harness.output.awaitFailure(AWAIT_TIMEOUT_MS);
        assertNotNull("an empty-CSD format size change must fail after publish"
                        + evidence(harness.output),
                failure);
        assertEquals(1, harness.output.getConfigCount());
        assertEquals(1, harness.output.getAccessUnitCount());
        harness.encoder.close();
    }

    @Test
    public void incompleteCsdColorChangeBeforeFirstAu_failsOnNextAu() {
        FormatHarness harness = new FormatHarness();
        harness.encoder.start();

        harness.enqueueFormat(1280, 720, BT709);
        assertNotNull(harness.output.awaitConfig(AWAIT_TIMEOUT_MS));

        harness.enqueueFormatWithoutCsd(1280, 720, BT2020);
        harness.fake.enqueueSample(idrSample(), 1_000L, true, false);

        EncoderException failure = harness.output.awaitFailure(AWAIT_TIMEOUT_MS);
        assertNotNull("an empty-CSD format color change must fail instead of reusing the old config"
                        + evidence(harness.output),
                failure);
        assertEquals(1, harness.output.getConfigCount());
        assertEquals(0, harness.output.getAccessUnitCount());
        harness.encoder.close();
    }

    @Test
    public void incompleteCsdColorChangeAfterPublish_failsOnNextAu() {
        FormatHarness harness = new FormatHarness();
        harness.encoder.start();

        harness.enqueueFormat(1280, 720, BT709);
        assertNotNull(harness.output.awaitConfig(AWAIT_TIMEOUT_MS));
        harness.fake.enqueueSample(idrSample(), 1_000L, true, false);
        assertNotNull(harness.output.awaitAccessUnit(AWAIT_TIMEOUT_MS));

        harness.enqueueFormatWithoutCsd(1280, 720, BT2020);
        harness.fake.enqueueSample(pSample(), 2_000L, false, false);

        EncoderException failure = harness.output.awaitFailure(AWAIT_TIMEOUT_MS);
        assertNotNull("an empty-CSD format color change must fail after publish"
                        + evidence(harness.output),
                failure);
        assertEquals(1, harness.output.getConfigCount());
        assertEquals(1, harness.output.getAccessUnitCount());
        harness.encoder.close();
    }

    @Test
    public void duplicateIdenticalConfig_sameSizeAndColor_isIgnored() {
        FormatHarness harness = new FormatHarness();
        harness.encoder.start();

        harness.enqueueFormat(1280, 720, BT709);
        assertNotNull(harness.output.awaitConfig(AWAIT_TIMEOUT_MS));
        harness.enqueueFormat(1280, 720, BT709);
        assertNull(harness.output.awaitFailure(QUIET_WINDOW_MS));

        harness.fake.enqueueSample(H264Fixtures.inBandConfigAndIdr(), 9_000L, true, false);

        EncodedAccessUnit accessUnit = harness.output.awaitAccessUnit(AWAIT_TIMEOUT_MS);
        assertNotNull(accessUnit);
        assertEquals(0L, accessUnit.getSequence());
        assertEquals(1, harness.output.getConfigCount());
        assertEquals(0, harness.output.getFailureCount());
        harness.encoder.close();
    }

    @Test
    public void colorUnspecifiedActualFallsBackToRequested() {
        FormatHarness harness = new FormatHarness(descriptor(BT709));
        harness.encoder.start();

        harness.enqueueFormat(WIDTH, HEIGHT, ColorInfo.UNSPECIFIED);

        EncoderConfig config = harness.output.awaitConfig(AWAIT_TIMEOUT_MS);
        assertNotNull(config);
        assertColor("an UNSPECIFIED actual color must fall back to the requested color",
                BT709, config.getColorInfo());
        harness.encoder.close();
    }

    // ---- I3: start/close serialization; release must never precede the start transition. ----

    @Test
    public void closeDuringStart_waitsForStartAndReleasesOnce() throws Exception {
        FakeMediaCodecEncoderBridge fake = new FakeMediaCodecEncoderBridge();
        LifecycleProbeBridge bridge = new LifecycleProbeBridge(fake);
        RecordingOutput output = new RecordingOutput();
        H264Encoder encoder = new H264Encoder(STREAM_KEY, descriptor(), PARAMETERS, output,
                new FixedBridgeFactory(bridge));
        bridge.blockStart();
        final AtomicReference<Throwable> startFailure = new AtomicReference<>();
        final CountDownLatch closeCallStarted = new CountDownLatch(1);
        Thread starter = new Thread(() -> {
            try {
                encoder.start();
            } catch (Throwable failure) {
                startFailure.set(failure);
            }
        }, "encoder-start");
        Thread closer = new Thread(() -> {
            closeCallStarted.countDown();
            encoder.close();
        }, "encoder-close");
        starter.setDaemon(true);
        closer.setDaemon(true);
        try {
            starter.start();
            assertTrue("start must reach bridge.start",
                    bridge.awaitStartEntered(AWAIT_TIMEOUT_MS));
            closer.start();
            assertTrue("close must be attempted",
                    closeCallStarted.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            assertFalse("close must not release the bridge while start is still on it",
                    bridge.awaitRelease(RELEASE_HOLD_WINDOW_MS));
            assertEquals(0, bridge.getReleaseCount());
        } finally {
            bridge.releaseStart();
            starter.join(AWAIT_TIMEOUT_MS);
            closer.join(AWAIT_TIMEOUT_MS);
        }

        assertNull("encoder.start must not fail", startFailure.get());
        assertEquals(1, bridge.getReleaseCount());
        int startReturn = bridge.indexOfEvent("start-return");
        int release = bridge.indexOfEvent("release");
        assertTrue("bridge.start must have returned", startReturn >= 0);
        assertTrue("the bridge must have been released", release >= 0);
        assertTrue("release must happen after bridge.start returns", startReturn < release);
        assertEquals("no drain loop may consume output after a close during start", 0,
                bridge.getDequeueCount());
        assertEquals(0, fake.getStopCount());
        assertEquals(0, output.getAccessUnitCount());
        encoder.close();
        assertEquals(1, fake.getReleaseCount());
        assertEquals(1, bridge.getReleaseCount());
    }

    @Test
    public void closeDuringBlockedStart_timesOutWithoutReleaseAndLaterCloseReleasesOnce()
            throws Exception {
        FakeMediaCodecEncoderBridge fake = new FakeMediaCodecEncoderBridge();
        LifecycleProbeBridge bridge = new LifecycleProbeBridge(fake);
        RecordingOutput output = new RecordingOutput();
        H264Encoder encoder = new H264Encoder(STREAM_KEY, descriptor(), PARAMETERS, output,
                new FixedBridgeFactory(bridge));
        bridge.blockStart();
        final AtomicReference<Throwable> startFailure = new AtomicReference<>();
        Thread starter = new Thread(() -> {
            try {
                encoder.start();
            } catch (Throwable failure) {
                startFailure.set(failure);
            }
        }, "encoder-start");
        starter.setDaemon(true);
        try {
            starter.start();
            assertTrue("start must reach bridge.start",
                    bridge.awaitStartEntered(AWAIT_TIMEOUT_MS));

            encoder.close();

            assertTrue("a close that times out must record the still-running start as a leak",
                    encoder.isLeakDetected());
            assertEquals("the bridge must not be released while start is still on it", 0,
                    bridge.getReleaseCount());
            assertFalse(encoder.isBridgeReleased());
        } finally {
            bridge.releaseStart();
            starter.join(AWAIT_TIMEOUT_MS);
            encoder.close();
        }

        assertNull("encoder.start must not fail", startFailure.get());
        assertEquals(1, bridge.getReleaseCount());
        assertTrue(encoder.isBridgeReleased());
        assertEquals(0, bridge.getDequeueCount());
        assertEquals(0, output.getAccessUnitCount());
        assertEquals(1, fake.getReleaseCount());
    }

    @Test
    public void startBridgeFailureWhileCloseWaiting_releasesOnceWithoutDrain() throws Exception {
        FakeMediaCodecEncoderBridge fake = new FakeMediaCodecEncoderBridge();
        LifecycleProbeBridge bridge = new LifecycleProbeBridge(fake);
        RecordingOutput output = new RecordingOutput();
        H264Encoder encoder = new H264Encoder(STREAM_KEY, descriptor(), PARAMETERS, output,
                new FixedBridgeFactory(bridge));
        bridge.blockStart();
        final AtomicReference<Throwable> startFailure = new AtomicReference<>();
        final CountDownLatch closeCallStarted = new CountDownLatch(1);
        Thread starter = new Thread(() -> {
            try {
                encoder.start();
            } catch (Throwable failure) {
                startFailure.set(failure);
            }
        }, "encoder-start");
        Thread closer = new Thread(() -> {
            closeCallStarted.countDown();
            encoder.close();
        }, "encoder-close");
        starter.setDaemon(true);
        closer.setDaemon(true);
        try {
            starter.start();
            assertTrue("start must reach bridge.start",
                    bridge.awaitStartEntered(AWAIT_TIMEOUT_MS));
            bridge.failNextStart(new RuntimeException("injected start failure"));
            closer.start();
            assertTrue("close must be attempted",
                    closeCallStarted.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            bridge.releaseStart();
            starter.join(AWAIT_TIMEOUT_MS);
            closer.join(AWAIT_TIMEOUT_MS);
        } finally {
            bridge.releaseStart();
            starter.join(AWAIT_TIMEOUT_MS);
            closer.join(AWAIT_TIMEOUT_MS);
        }

        assertTrue("a bridge start failure must surface as EncoderException",
                startFailure.get() instanceof EncoderException);
        encoder.close();
        assertEquals(1, bridge.getReleaseCount());
        assertEquals(1, fake.getReleaseCount());
        assertEquals(0, bridge.getDequeueCount());
        assertEquals(0, output.getAccessUnitCount());
    }

    @Test
    public void stopAfterClose_doesNotCallBridgeStopAfterRelease() {
        Harness harness = new Harness();
        H264Encoder encoder = harness.newStartedEncoder();
        encoder.close();
        assertEquals(1, harness.bridge.getReleaseCount());
        int stopsAfterClose = harness.bridge.getStopCount();

        encoder.stop();

        assertEquals("stop must not touch the bridge after release", stopsAfterClose,
                harness.bridge.getStopCount());
        assertEquals(1, harness.bridge.getReleaseCount());
    }

    private static StreamDescriptor descriptor() {
        return descriptor(ColorInfo.UNSPECIFIED);
    }

    private static StreamDescriptor descriptor(ColorInfo colorInfo) {
        return StreamDescriptor.builder(STREAM_KEY, CONFIG_EPOCH, "h264", H264_MIME,
                        BitstreamFormat.ANNEX_B)
                .profile("baseline")
                .level("3.1")
                .codedSize(WIDTH, HEIGHT)
                .visibleSize(WIDTH, HEIGHT)
                .colorInfo(colorInfo)
                .configParts(Collections.<CodecConfigPart>emptyList())
                .build();
    }

    private static void assertColor(String message, ColorInfo expected, ColorInfo actual) {
        assertNotNull(actual);
        assertEquals(message, expected.getColorSpace(), actual.getColorSpace());
        assertEquals(message, expected.getTransferFunction(), actual.getTransferFunction());
        assertEquals(message, expected.isFullRange(), actual.isFullRange());
    }

    /**
     * Renders the callback counters of a failed-expectation message, useful in the RED log.
     */
    private static String evidence(RecordingOutput output) {
        return " (failureCount=" + output.getFailureCount()
                + ", configCount=" + output.getConfigCount()
                + ", accessUnitCount=" + output.getAccessUnitCount() + ")";
    }

    private static byte[] idrSample() {
        return H264Fixtures.annexB(H264Fixtures.idrSlice());
    }

    private static byte[] pSample() {
        return H264Fixtures.annexB(H264Fixtures.pSlice());
    }

    /**
     * Same PPS and IDR as {@link #idrSample()} with a modified SPS, i.e. a different config.
     */
    private static byte[] changedConfigIdrSample() {
        byte[] changedSps = H264Fixtures.sps();
        changedSps[changedSps.length - 1] ^= 0x01;
        return H264Fixtures.annexB(changedSps, H264Fixtures.pps(), H264Fixtures.idrSlice());
    }

    private static List<Integer> nalTypes(byte[] annexB) {
        List<Integer> types = new ArrayList<Integer>();
        for (H264NalUnit unit : new H264AnnexBParser().parse(annexB)) {
            types.add(Integer.valueOf(unit.getType()));
        }
        return types;
    }

    private static final class Harness {

        private final FakeMediaCodecEncoderBridge bridge = new FakeMediaCodecEncoderBridge();
        private final RecordingOutput output = new RecordingOutput();
        private final FixedBridgeFactory factory = new FixedBridgeFactory(bridge);
        private final StreamParameters parameters;

        Harness() {
            this(PARAMETERS);
        }

        Harness(StreamParameters parameters) {
            this.parameters = parameters;
        }

        H264Encoder newEncoder() {
            return new H264Encoder(STREAM_KEY, descriptor(), parameters, output, factory);
        }

        H264Encoder newStartedEncoder() {
            H264Encoder encoder = newEncoder();
            encoder.start();
            return encoder;
        }
    }

    /**
     * Bridge factory that always hands out one pre-built bridge, so a test can inject a failure
     * before {@link H264Encoder}'s constructor runs.
     */
    private static final class FixedBridgeFactory implements MediaCodecBridgeFactory {

        private final MediaCodecEncoderBridge encoderBridge;
        private final List<String> encoderMimes =
                Collections.synchronizedList(new ArrayList<String>());

        FixedBridgeFactory(MediaCodecEncoderBridge encoderBridge) {
            this.encoderBridge = encoderBridge;
        }

        @Override
        public MediaCodecEncoderBridge createEncoder(String mime) {
            encoderMimes.add(mime);
            return encoderBridge;
        }

        @Override
        public MediaCodecDecoderBridge createDecoder(String mime) {
            throw new UnsupportedOperationException("H264Encoder must not create a decoder");
        }

        List<String> getEncoderMimes() {
            synchronized (encoderMimes) {
                return new ArrayList<String>(encoderMimes);
            }
        }
    }

    /**
     * Delegating bridge used only to fail {@link #requestSyncFrame()}: the shared JVM fake has no
     * failure injection for that call, and this test file may not extend the fake.
     */
    private static final class RequestSyncFailingBridge implements MediaCodecEncoderBridge {

        private final MediaCodecEncoderBridge delegate;
        private volatile RuntimeException requestSyncFrameFailure;

        RequestSyncFailingBridge(MediaCodecEncoderBridge delegate) {
            this.delegate = delegate;
        }

        void failNextRequestSyncFrame(RuntimeException failure) {
            this.requestSyncFrameFailure = failure;
        }

        @Override
        public void configure(EncoderFormat format) {
            delegate.configure(format);
        }

        @Override
        public android.view.Surface createInputSurface() {
            return delegate.createInputSurface();
        }

        @Override
        public void start() {
            delegate.start();
        }

        @Override
        public void stop() {
            delegate.stop();
        }

        @Override
        public void release() {
            delegate.release();
        }

        @Override
        public void requestSyncFrame() {
            RuntimeException failure = requestSyncFrameFailure;
            if (failure != null) {
                requestSyncFrameFailure = null;
                throw failure;
            }
            delegate.requestSyncFrame();
        }

        @Override
        public void updateBitrate(int bitrateBps) {
            delegate.updateBitrate(bitrateBps);
        }

        @Override
        public EncodedSample dequeueOutput(long timeoutUs) {
            return delegate.dequeueOutput(timeoutUs);
        }

        @Override
        public EncoderStreamFormat getOutputFormat() {
            return delegate.getOutputFormat();
        }
    }

    /**
     * Delegating bridge whose {@link #stop()} always throws; used to prove the drain thread's
     * best-effort stop is swallowed and does not break stop/close.
     */
    private static final class StopFailingBridge implements MediaCodecEncoderBridge {

        private final MediaCodecEncoderBridge delegate;
        private final AtomicInteger stopAttempts = new AtomicInteger();

        StopFailingBridge(MediaCodecEncoderBridge delegate) {
            this.delegate = delegate;
        }

        int getStopAttempts() {
            return stopAttempts.get();
        }

        @Override
        public void configure(EncoderFormat format) {
            delegate.configure(format);
        }

        @Override
        public android.view.Surface createInputSurface() {
            return delegate.createInputSurface();
        }

        @Override
        public void start() {
            delegate.start();
        }

        @Override
        public void stop() {
            stopAttempts.incrementAndGet();
            throw new RuntimeException("bridge stop failed");
        }

        @Override
        public void release() {
            delegate.release();
        }

        @Override
        public void requestSyncFrame() {
            delegate.requestSyncFrame();
        }

        @Override
        public void updateBitrate(int bitrateBps) {
            delegate.updateBitrate(bitrateBps);
        }

        @Override
        public EncodedSample dequeueOutput(long timeoutUs) {
            return delegate.dequeueOutput(timeoutUs);
        }

        @Override
        public EncoderStreamFormat getOutputFormat() {
            return delegate.getOutputFormat();
        }
    }

    /**
     * Harness for tests that need a specific output {@link EncoderStreamFormat}: the shared fake can
     * only report {@link ColorInfo#UNSPECIFIED} and a size chosen by its enqueue helper. The wrapper
     * overrides only {@link #getOutputFormat()}; every other call is delegated.
     */
    private static final class FormatHarness {

        private final FakeMediaCodecEncoderBridge fake = new FakeMediaCodecEncoderBridge();
        private final OutputFormatBridge bridge = new OutputFormatBridge(fake);
        private final RecordingOutput output = new RecordingOutput();
        private final H264Encoder encoder;

        FormatHarness() {
            this(descriptor());
        }

        FormatHarness(StreamDescriptor requestedDescriptor) {
            this.encoder = new H264Encoder(STREAM_KEY, requestedDescriptor, PARAMETERS, output,
                    new FixedBridgeFactory(bridge));
        }

        void enqueueFormat(int width, int height, ColorInfo colorInfo) {
            bridge.setFormat(new EncoderStreamFormat(
                    H264Fixtures.sps(), H264Fixtures.pps(), width, height, colorInfo));
            fake.enqueueSample(new byte[0], 0L, false, false, true, false);
        }

        void enqueueFormatWithoutCsd(int width, int height, ColorInfo colorInfo) {
            bridge.setFormat(new EncoderStreamFormat(null, null, width, height, colorInfo));
            fake.enqueueSample(new byte[0], 0L, false, false, true, false);
        }
    }

    /**
     * Delegating bridge that serves a caller-supplied output-format snapshot.
     */
    private static final class OutputFormatBridge implements MediaCodecEncoderBridge {

        private final MediaCodecEncoderBridge delegate;
        private volatile EncoderStreamFormat outputFormat;

        OutputFormatBridge(MediaCodecEncoderBridge delegate) {
            this.delegate = delegate;
        }

        void setFormat(EncoderStreamFormat format) {
            this.outputFormat = format;
        }

        @Override
        public EncoderStreamFormat getOutputFormat() {
            EncoderStreamFormat format = outputFormat;
            return format != null ? format : delegate.getOutputFormat();
        }

        @Override
        public void configure(EncoderFormat format) {
            delegate.configure(format);
        }

        @Override
        public android.view.Surface createInputSurface() {
            return delegate.createInputSurface();
        }

        @Override
        public void start() {
            delegate.start();
        }

        @Override
        public void stop() {
            delegate.stop();
        }

        @Override
        public void release() {
            delegate.release();
        }

        @Override
        public void requestSyncFrame() {
            delegate.requestSyncFrame();
        }

        @Override
        public void updateBitrate(int bitrateBps) {
            delegate.updateBitrate(bitrateBps);
        }

        @Override
        public EncodedSample dequeueOutput(long timeoutUs) {
            return delegate.dequeueOutput(timeoutUs);
        }
    }

    /**
     * Delegating bridge that can hold {@link #start()} open, fail it on demand, and record the
     * order of the lifecycle calls that decide the start/close race. Used only to inject failures;
     * the shared fake is not modified.
     */
    private static final class LifecycleProbeBridge implements MediaCodecEncoderBridge {

        private final MediaCodecEncoderBridge delegate;
        private final List<String> events = Collections.synchronizedList(new ArrayList<String>());
        private final CountDownLatch startEntered = new CountDownLatch(1);
        private final CountDownLatch startRelease = new CountDownLatch(1);
        private final CountDownLatch firstRelease = new CountDownLatch(1);
        private final AtomicInteger startCount = new AtomicInteger();
        private final AtomicInteger stopCount = new AtomicInteger();
        private final AtomicInteger releaseCount = new AtomicInteger();
        private final AtomicInteger dequeueCount = new AtomicInteger();
        private volatile boolean blockNextStart;
        private volatile RuntimeException nextStartFailure;

        LifecycleProbeBridge(MediaCodecEncoderBridge delegate) {
            this.delegate = delegate;
        }

        void blockStart() {
            this.blockNextStart = true;
        }

        void failNextStart(RuntimeException failure) {
            this.nextStartFailure = failure;
        }

        boolean awaitStartEntered(long timeoutMs) {
            try {
                return startEntered.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while awaiting start", e);
            }
        }

        void releaseStart() {
            startRelease.countDown();
        }

        boolean awaitRelease(long timeoutMs) {
            try {
                return firstRelease.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while awaiting release", e);
            }
        }

        int getReleaseCount() {
            return releaseCount.get();
        }

        int getDequeueCount() {
            return dequeueCount.get();
        }

        int indexOfEvent(String event) {
            synchronized (events) {
                return events.indexOf(event);
            }
        }

        @Override
        public void start() {
            events.add("start-enter");
            startCount.incrementAndGet();
            if (blockNextStart) {
                blockNextStart = false;
                startEntered.countDown();
                try {
                    startRelease.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            RuntimeException failure = nextStartFailure;
            if (failure != null) {
                nextStartFailure = null;
                events.add("start-throw");
                throw failure;
            }
            delegate.start();
            events.add("start-return");
        }

        @Override
        public void stop() {
            stopCount.incrementAndGet();
            events.add("stop");
            delegate.stop();
        }

        @Override
        public void release() {
            releaseCount.incrementAndGet();
            events.add("release");
            firstRelease.countDown();
            delegate.release();
        }

        @Override
        public void configure(EncoderFormat format) {
            delegate.configure(format);
        }

        @Override
        public android.view.Surface createInputSurface() {
            return delegate.createInputSurface();
        }

        @Override
        public void requestSyncFrame() {
            delegate.requestSyncFrame();
        }

        @Override
        public void updateBitrate(int bitrateBps) {
            delegate.updateBitrate(bitrateBps);
        }

        @Override
        public EncodedSample dequeueOutput(long timeoutUs) {
            dequeueCount.incrementAndGet();
            return delegate.dequeueOutput(timeoutUs);
        }

        @Override
        public EncoderStreamFormat getOutputFormat() {
            return delegate.getOutputFormat();
        }
    }

    /**
     * Encoder output that records callbacks and exposes bounded waits. The config callback can be
     * held open so a test can observe what {@link H264Encoder#close()} does while the drain thread
     * is still alive.
     */
    private static final class RecordingOutput implements EncoderOutput {

        private final BlockingQueue<EncoderConfig> configs = new LinkedBlockingQueue<>();
        private final BlockingQueue<EncodedAccessUnit> accessUnits = new LinkedBlockingQueue<>();
        private final BlockingQueue<EncoderException> failures = new LinkedBlockingQueue<>();
        private final List<String> events =
                Collections.synchronizedList(new ArrayList<String>());
        private final AtomicInteger configCount = new AtomicInteger();
        private final AtomicInteger accessUnitCount = new AtomicInteger();
        private final AtomicInteger failureCount = new AtomicInteger();
        private final AtomicReference<RuntimeException> accessUnitFailure = new AtomicReference<>();
        private final AtomicReference<RuntimeException> configFailure = new AtomicReference<>();

        private volatile CountDownLatch configGate;
        private volatile CountDownLatch activeConfigGate;
        private volatile CountDownLatch configGateEntered;

        @Override
        public void onAccessUnit(EncodedAccessUnit accessUnit) {
            RuntimeException failure = accessUnitFailure.getAndSet(null);
            if (failure != null) {
                throw failure;
            }
            accessUnitCount.incrementAndGet();
            events.add("au");
            accessUnits.add(accessUnit);
        }

        @Override
        public void onEncoderConfig(EncoderConfig config) {
            CountDownLatch gate = configGate;
            if (gate != null) {
                configGate = null;
                activeConfigGate = gate;
                CountDownLatch entered = configGateEntered;
                if (entered != null) {
                    entered.countDown();
                }
                try {
                    gate.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                activeConfigGate = null;
            }
            RuntimeException failure = configFailure.getAndSet(null);
            if (failure != null) {
                throw failure;
            }
            configCount.incrementAndGet();
            events.add("config");
            configs.add(config);
        }

        @Override
        public void onEncoderFailure(EncoderException failure) {
            failureCount.incrementAndGet();
            events.add("failure");
            failures.add(failure);
        }

        EncoderConfig awaitConfig(long timeoutMs) {
            try {
                return configs.poll(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while awaiting encoder config", e);
            }
        }

        EncodedAccessUnit awaitAccessUnit(long timeoutMs) {
            try {
                return accessUnits.poll(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while awaiting access unit", e);
            }
        }

        EncoderException awaitFailure(long timeoutMs) {
            try {
                return failures.poll(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while awaiting encoder failure", e);
            }
        }

        int getConfigCount() {
            return configCount.get();
        }

        int getAccessUnitCount() {
            return accessUnitCount.get();
        }

        int getFailureCount() {
            return failureCount.get();
        }

        List<String> getEvents() {
            synchronized (events) {
                return new ArrayList<String>(events);
            }
        }

        void failNextAccessUnit(RuntimeException failure) {
            accessUnitFailure.set(failure);
        }

        void blockNextConfigCallback() {
            configGate = new CountDownLatch(1);
            configGateEntered = new CountDownLatch(1);
        }

        boolean awaitConfigCallbackEntered(long timeoutMs) {
            CountDownLatch entered = configGateEntered;
            if (entered == null) {
                return false;
            }
            try {
                return entered.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "interrupted while awaiting encoder config callback", e);
            }
        }

        void releaseBlockedConfigCallback() {
            CountDownLatch gate = activeConfigGate;
            if (gate == null) {
                gate = configGate;
            }
            if (gate != null) {
                gate.countDown();
            }
        }
    }
}
