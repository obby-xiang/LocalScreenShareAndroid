package com.obby.android.localscreenshare.core.codec.h264;

import android.view.Surface;

import com.obby.android.localscreenshare.core.codec.CodecIds;
import com.obby.android.localscreenshare.core.codec.DecoderException;
import com.obby.android.localscreenshare.core.model.AccessUnitFlags;
import com.obby.android.localscreenshare.core.model.BitstreamFormat;
import com.obby.android.localscreenshare.core.model.CodecConfigPart;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.JoinInfo;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.render.EncodedImageFrame;
import com.obby.android.localscreenshare.core.render.RenderTarget;
import com.obby.android.localscreenshare.core.support.FakeMediaCodecDecoderBridge;
import com.obby.android.localscreenshare.core.support.FakeRenderTarget;
import com.obby.android.localscreenshare.core.support.H264Fixtures;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Behavioural tests for {@link H264Decoder} driven by the JVM decoder bridge fake.
 *
 * <p>No Android {@link Surface} is created. The render target returns {@code null} on the JVM,
 * so surface propagation is asserted by reference identity of whatever the target returns; real
 * Surface behaviour is outside this test's evidence.</p>
 */
public class H264DecoderTest {

    private static final StreamKey STREAM_KEY = new StreamKey("session-6", "stream-6");

    private static final String H264_MIME = "video/avc";

    private static final int CONFIG_EPOCH = 2;

    private static final int WIDTH = 640;

    private static final int HEIGHT = 480;

    private static final long PTS_US = 33_333L;

    private FakeRenderTarget renderTarget;

    private FakeMediaCodecDecoderBridge bridge;

    private FixedBridgeFactory bridgeFactory;

    private H264Decoder decoder;

    @Before
    public void setUp() {
        renderTarget = new FakeRenderTarget();
        bridge = new FakeMediaCodecDecoderBridge();
        bridgeFactory = new FixedBridgeFactory(bridge);
        decoder = newDecoder(bridgeFactory, renderTarget);
    }

    @Test
    public void configure_requiresSpsAndPps() {
        assertConfigureRejected(configParts());
        assertConfigureRejected(configParts(part("sps", H264Fixtures.sps())));
        assertConfigureRejected(configParts(part("pps", H264Fixtures.pps())));
    }

    @Test
    public void configure_rejectsDuplicateSps() {
        byte[] secondSps = H264Fixtures.sps();
        secondSps[secondSps.length - 1] ^= 0x01;
        assertConfigureRejected(configParts(
                part("sps", H264Fixtures.sps()),
                part("sps", secondSps),
                part("pps", H264Fixtures.pps())));
        assertConfigureRejected(configParts(
                part("sps", H264Fixtures.sps()),
                part("pps", H264Fixtures.pps()),
                part("pps", H264Fixtures.pps())));
    }

    @Test
    public void configure_validatesDescriptorBeforeSurfaceAndBridge() {
        assertRejectedBeforeSurfaceOrBridge("jpeg", validConfigParts());
        assertRejectedBeforeSurfaceOrBridge(CodecIds.H264, configParts());
        assertRejectedBeforeSurfaceOrBridge(CodecIds.H264,
                configParts(part("sps", H264Fixtures.sps())));
        assertRejectedBeforeSurfaceOrBridge(CodecIds.H264,
                configParts(part("sps", H264Fixtures.sps()),
                        part("sps", H264Fixtures.sps()),
                        part("pps", H264Fixtures.pps())));
    }

    @Test
    public void configure_passesRenderTargetSurfaceIntoDecoderFormat() {
        CountingRenderTarget target = new CountingRenderTarget();
        FakeMediaCodecDecoderBridge fixedBridge = new FakeMediaCodecDecoderBridge();
        H264Decoder fixedDecoder = newDecoder(new FixedBridgeFactory(fixedBridge), target);
        Surface expectedSurface = target.getSurface();

        fixedDecoder.configure(descriptor(CONFIG_EPOCH, CodecIds.H264,
                validConfigParts()));

        assertTrue(target.getSurfaceReads() >= 2);
        DecoderFormat format = fixedBridge.getLastConfigureFormat();
        assertNotNull(format);
        assertEquals(WIDTH, format.getWidth());
        assertEquals(HEIGHT, format.getHeight());
        assertArrayEquals(H264ConfigExtractor.toAnnexB(H264Fixtures.sps()), format.getCsd0());
        assertArrayEquals(H264ConfigExtractor.toAnnexB(H264Fixtures.pps()), format.getCsd1());
        assertSame(expectedSurface, format.getOutputSurface());
        assertEquals(1, fixedBridge.getConfigureCount());
        assertEquals(1, fixedBridge.getStartCount());
    }

    @Test
    public void configure_configureFailure_closesOnceAndThrowsDecoderException() {
        bridge.failNextConfigure(new RuntimeException("configure failed"));

        assertThrows(DecoderException.class,
                () -> decoder.configure(descriptor(CONFIG_EPOCH, CodecIds.H264,
                        validConfigParts())));

        assertEquals(0, bridge.getConfigureCount());
        assertEquals(0, bridge.getStartCount());
        assertEquals(1, bridge.getReleaseCount());

        decoder.close();
        assertEquals(1, bridge.getReleaseCount());
        assertEquals(0, renderTarget.getCloseCount());
    }

    @Test
    public void configure_startFailure_closesOnceAndThrowsDecoderException() {
        bridge.failNextStart(new RuntimeException("start failed"));

        assertThrows(DecoderException.class,
                () -> decoder.configure(descriptor(CONFIG_EPOCH, CodecIds.H264,
                        validConfigParts())));

        assertEquals(1, bridge.getConfigureCount());
        assertEquals(0, bridge.getStartCount());
        assertEquals(1, bridge.getReleaseCount());

        decoder.close();
        assertEquals(1, bridge.getReleaseCount());
    }

    @Test
    public void firstNonIdr_isDroppedWithoutExceptionOrStateChange() {
        decoder.configure(descriptor(CONFIG_EPOCH, CodecIds.H264, validConfigParts()));

        decoder.decode(accessUnit(CONFIG_EPOCH, 0, H264Fixtures.annexB(H264Fixtures.pSlice())));

        assertTrue(bridge.getQueuedInputData().isEmpty());
        assertEquals(0, bridge.getDequeueInputCount());

        decoder.decode(accessUnit(CONFIG_EPOCH, 1, idrPayload()));

        assertEquals(1, bridge.getQueuedInputData().size());
        assertArrayEquals(idrPayload(), bridge.getQueuedInputData().get(0));
    }

    @Test
    public void configPlusIdrInSameAu_queuesIdrAfterStrippingConfig() {
        decoder.configure(descriptor(CONFIG_EPOCH, CodecIds.H264, validConfigParts()));
        byte[] payload = H264Fixtures.inBandConfigAndIdr();

        decoder.decode(accessUnit(CONFIG_EPOCH, 7, payload));

        assertEquals(1, bridge.getQueuedInputData().size());
        assertArrayEquals(H264AnnexBParser.stripSpsPps(payload),
                bridge.getQueuedInputData().get(0));
        assertEquals(Collections.singletonList(5), nalTypes(bridge.getQueuedInputData().get(0)));
        assertEquals(Long.valueOf(PTS_US), bridge.getQueuedInputPtsUs().get(0));
    }

    @Test
    public void configOnlyPayload_isIgnored() {
        decoder.configure(descriptor(CONFIG_EPOCH, CodecIds.H264, validConfigParts()));
        decoder.decode(accessUnit(CONFIG_EPOCH, 0, idrPayload()));
        assertEquals(1, bridge.getQueuedInputData().size());
        int dequeueCallsAfterIdr = bridge.getDequeueInputCount();

        byte[] configOnly = H264Fixtures.annexB(H264Fixtures.sps(), H264Fixtures.pps());
        decoder.decode(accessUnit(CONFIG_EPOCH, 1, configOnly));

        byte[] seiOnly = H264Fixtures.annexB(new byte[]{0x06, 0x05, 0x01, (byte) 0x80});
        assertTrue(H264AnnexBParser.stripSpsPps(seiOnly).length > 0);
        assertTrue(!H264AnnexBParser.analyze(seiOnly).hasVcl());
        decoder.decode(accessUnit(CONFIG_EPOCH, 2, seiOnly));

        assertEquals(1, bridge.getQueuedInputData().size());
        assertEquals(dequeueCallsAfterIdr, bridge.getDequeueInputCount());

        decoder.decode(accessUnit(CONFIG_EPOCH, 3, idrPayload()));
        assertEquals(2, bridge.getQueuedInputData().size());
    }

    @Test
    public void bSlice_isRejectedEvenWhenNotFirstVcl() {
        decoder.configure(descriptor(CONFIG_EPOCH, CodecIds.H264, validConfigParts()));
        byte[] payload = H264Fixtures.annexB(H264Fixtures.idrSlice(), H264Fixtures.bSlice());

        assertThrows(DecoderException.class,
                () -> decoder.decode(accessUnit(CONFIG_EPOCH, 0, payload)));

        assertTrue(bridge.getQueuedInputData().isEmpty());
        assertEquals(0, bridge.getDequeueInputCount());
    }

    @Test
    public void epochMismatch_isRejectedBeforeBridgeIo() {
        assertThrows(DecoderException.class,
                () -> decoder.decode(accessUnit(CONFIG_EPOCH, 0, idrPayload())));
        assertEquals(0, bridge.getDequeueInputCount());

        decoder.configure(descriptor(CONFIG_EPOCH, CodecIds.H264, validConfigParts()));

        assertThrows(DecoderException.class,
                () -> decoder.decode(accessUnit(CONFIG_EPOCH - 1, 0, idrPayload())));
        assertThrows(DecoderException.class,
                () -> decoder.decode(accessUnit(CONFIG_EPOCH + 1, 0, idrPayload())));

        assertEquals(0, bridge.getDequeueInputCount());
        assertTrue(bridge.getQueuedInputData().isEmpty());
    }

    @Test
    public void inputTimeoutNonJoin_isDroppedWithoutException() {
        BudgetDecoderBridge budgetBridge = new BudgetDecoderBridge(1);
        H264Decoder budgetDecoder = newDecoder(new BudgetBridgeFactory(budgetBridge), renderTarget);
        budgetDecoder.configure(descriptor(CONFIG_EPOCH, CodecIds.H264, validConfigParts()));

        budgetDecoder.decode(accessUnit(CONFIG_EPOCH, 0, idrPayload()));
        assertEquals(1, budgetBridge.getQueuedInputCount());

        budgetDecoder.decode(accessUnit(CONFIG_EPOCH, 1,
                H264Fixtures.annexB(H264Fixtures.pSlice())));

        assertEquals(1, budgetBridge.getQueuedInputCount());
        assertTrue(budgetBridge.getDequeueInputCalls() > 1);
    }

    @Test
    public void inputTimeoutJoin_throwsDecoderException() {
        BudgetDecoderBridge budgetBridge = new BudgetDecoderBridge(1);
        H264Decoder budgetDecoder = newDecoder(new BudgetBridgeFactory(budgetBridge), renderTarget);
        budgetDecoder.configure(descriptor(CONFIG_EPOCH, CodecIds.H264, validConfigParts()));
        budgetDecoder.decode(accessUnit(CONFIG_EPOCH, 0, idrPayload()));

        DecoderException failure = assertThrows(DecoderException.class,
                () -> budgetDecoder.decode(joinAccessUnit(CONFIG_EPOCH, 1, idrPayload())));

        assertNotNull(failure.getMessage());
        assertTrue(failure.getMessage().contains("join point input timeout"));
        assertNull(failure.getCause());
        assertEquals(1, budgetBridge.getQueuedInputCount());
    }

    @Test
    public void queuedThenOutputFailure_throwsDecoderException() {
        decoder.configure(descriptor(CONFIG_EPOCH, CodecIds.H264, validConfigParts()));
        bridge.failNextDequeue(new RuntimeException("output failed"));

        DecoderException failure = assertThrows(DecoderException.class,
                () -> decoder.decode(accessUnit(CONFIG_EPOCH, 0, idrPayload())));

        assertNotNull(failure.getCause());
        assertEquals(1, bridge.getQueuedInputData().size());
        assertEquals(0, bridge.getReleaseCount());
    }

    @Test
    public void close_isIdempotentReleasesOnceAndDoesNotCloseRenderTarget() {
        decoder.configure(descriptor(CONFIG_EPOCH, CodecIds.H264, validConfigParts()));

        decoder.close();
        decoder.close();

        assertEquals(1, bridge.getStopCount());
        assertEquals(1, bridge.getReleaseCount());
        assertEquals(0, renderTarget.getCloseCount());
    }

    private H264Decoder newDecoder(MediaCodecBridgeFactory factory, RenderTarget target) {
        return new H264Decoder(STREAM_KEY, descriptor(CONFIG_EPOCH, CodecIds.H264,
                validConfigParts()), target, factory);
    }

    private void assertConfigureRejected(List<CodecConfigPart> parts) {
        H264Decoder rejected = newDecoder(bridgeFactory, renderTarget);
        assertThrows(DecoderException.class,
                () -> rejected.configure(descriptor(CONFIG_EPOCH, CodecIds.H264, parts)));
    }

    private void assertRejectedBeforeSurfaceOrBridge(String codecId,
                                                     List<CodecConfigPart> parts) {
        CountingRenderTarget target = new CountingRenderTarget();
        CountingBridgeFactory factory = new CountingBridgeFactory();
        H264Decoder rejected = newDecoder(factory, target);

        assertThrows(DecoderException.class,
                () -> rejected.configure(descriptor(CONFIG_EPOCH, codecId, parts)));

        assertEquals(0, target.getSurfaceReads());
        assertEquals(0, factory.getCreateDecoderCount());
    }

    private static EncodedAccessUnit accessUnit(int configEpoch, long sequence, byte[] payload) {
        return new EncodedAccessUnit(STREAM_KEY, configEpoch, sequence, PTS_US,
                EncodedAccessUnit.DTS_UNSET, AccessUnitFlags.none(), null, null, payload);
    }

    private static EncodedAccessUnit joinAccessUnit(int configEpoch, long sequence,
                                                    byte[] payload) {
        return new EncodedAccessUnit(STREAM_KEY, configEpoch, sequence, PTS_US,
                EncodedAccessUnit.DTS_UNSET, AccessUnitFlags.of(true, true, false),
                new JoinInfo(configEpoch, Collections.emptyList()), null, payload);
    }

    private static byte[] idrPayload() {
        return H264Fixtures.annexB(H264Fixtures.idrSlice());
    }

    private static StreamDescriptor descriptor(int configEpoch, String codecId,
                                               List<CodecConfigPart> parts) {
        return StreamDescriptor.builder(STREAM_KEY, configEpoch, codecId, H264_MIME,
                        BitstreamFormat.ANNEX_B)
                .codedSize(WIDTH, HEIGHT)
                .visibleSize(WIDTH, HEIGHT)
                .configParts(parts)
                .build();
    }

    private static List<CodecConfigPart> validConfigParts() {
        return configParts(
                part("sps", H264Fixtures.sps()),
                part("pps", H264Fixtures.pps()));
    }

    private static CodecConfigPart part(String id, byte[] payload) {
        return new CodecConfigPart(id, payload);
    }

    private static List<CodecConfigPart> configParts(CodecConfigPart... parts) {
        return Collections.unmodifiableList(new ArrayList<>(Arrays.asList(parts)));
    }

    private static List<Integer> nalTypes(byte[] annexB) {
        List<Integer> types = new ArrayList<>();
        for (H264NalUnit unit : H264AnnexBParser.parse(annexB)) {
            types.add(Integer.valueOf(unit.getType()));
        }
        return types;
    }

    private static final class FixedBridgeFactory implements MediaCodecBridgeFactory {

        private final FakeMediaCodecDecoderBridge decoder;

        FixedBridgeFactory(FakeMediaCodecDecoderBridge decoder) {
            this.decoder = decoder;
        }

        @Override
        public MediaCodecEncoderBridge createEncoder(String mime) {
            throw new UnsupportedOperationException("encoder is not used by decoder tests");
        }

        @Override
        public MediaCodecDecoderBridge createDecoder(String mime) {
            return decoder;
        }
    }

    private static final class BudgetBridgeFactory implements MediaCodecBridgeFactory {

        private final BudgetDecoderBridge decoder;

        BudgetBridgeFactory(BudgetDecoderBridge decoder) {
            this.decoder = decoder;
        }

        @Override
        public MediaCodecEncoderBridge createEncoder(String mime) {
            throw new UnsupportedOperationException("encoder is not used by decoder tests");
        }

        @Override
        public MediaCodecDecoderBridge createDecoder(String mime) {
            return decoder;
        }
    }

    private static final class CountingBridgeFactory implements MediaCodecBridgeFactory {

        private int createDecoderCount;

        @Override
        public MediaCodecEncoderBridge createEncoder(String mime) {
            throw new AssertionError("bridge must not be created before validation");
        }

        @Override
        public MediaCodecDecoderBridge createDecoder(String mime) {
            createDecoderCount++;
            throw new AssertionError("bridge must not be created before validation");
        }

        int getCreateDecoderCount() {
            return createDecoderCount;
        }
    }

    private static final class CountingRenderTarget implements RenderTarget {

        private int surfaceReads;

        @Override
        public Surface getSurface() {
            surfaceReads++;
            return null;
        }

        @Override
        public void renderEncodedImage(EncodedImageFrame frame) {
        }

        int getSurfaceReads() {
            return surfaceReads;
        }
    }

    /**
     * Decoder bridge that hands out a bounded number of input buffers and then reports an input
     * timeout forever. This models a stalled consumer without modifying the shared bridge fake.
     */
    private static final class BudgetDecoderBridge implements MediaCodecDecoderBridge {

        private final int allowedInputBuffers;

        private final List<byte[]> queued = new ArrayList<>();

        private int dequeueInputCalls;

        BudgetDecoderBridge(int allowedInputBuffers) {
            this.allowedInputBuffers = allowedInputBuffers;
        }

        @Override
        public void configure(DecoderFormat format) {
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public void release() {
        }

        @Override
        public int dequeueInputBuffer(long timeoutUs) {
            dequeueInputCalls++;
            if (dequeueInputCalls <= allowedInputBuffers) {
                return dequeueInputCalls - 1;
            }
            return -1;
        }

        @Override
        public void queueInputBuffer(int index, byte[] data, long ptsUs) {
            queued.add(data.clone());
        }

        @Override
        public DecodedOutput dequeueOutput(long timeoutUs) {
            return null;
        }

        int getDequeueInputCalls() {
            return dequeueInputCalls;
        }

        int getQueuedInputCount() {
            return queued.size();
        }
    }
}
