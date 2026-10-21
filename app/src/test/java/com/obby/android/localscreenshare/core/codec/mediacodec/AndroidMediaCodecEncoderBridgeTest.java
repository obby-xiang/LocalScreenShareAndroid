package com.obby.android.localscreenshare.core.codec.mediacodec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unit tests for the frame-rate cap plumbing of {@link AndroidMediaCodecEncoderBridge}.
 *
 * <p>The codec itself cannot exist on the JVM, so the fallback is exercised through the package
 * private seam: a sink that rejects the capped format the way a vendor encoder would.</p>
 */
public class AndroidMediaCodecEncoderBridgeTest {

    private static final EncoderFormat CAPPED = new EncoderFormat(1080, 2340, 9_500_000, 15, 3, 15f);

    @Test
    public void configure_appliesTheCapWhenTheCodecAcceptsIt() {
        List<EncoderFormat> applied = new ArrayList<>();
        AtomicInteger resets = new AtomicInteger();

        AndroidMediaCodecEncoderBridge.configureWithFallback(CAPPED, applied::add,
                resets::incrementAndGet);

        assertEquals(1, applied.size());
        assertEquals(15f, applied.get(0).getMaxFpsToEncoder(), 0f);
        assertEquals(0, resets.get());
    }

    @Test
    public void configure_retriesWithoutTheCapAfterAReset() {
        List<EncoderFormat> applied = new ArrayList<>();
        AtomicInteger resets = new AtomicInteger();

        AndroidMediaCodecEncoderBridge.configureWithFallback(CAPPED, format -> {
            if (format.getMaxFpsToEncoder() > 0f) {
                throw new IllegalArgumentException("unsupported key: max-fps-to-encoder");
            }
            applied.add(format);
        }, resets::incrementAndGet);

        assertEquals(1, applied.size());
        assertEquals(0f, applied.get(0).getMaxFpsToEncoder(), 0f);
        assertEquals("the codec must be reset between the attempts", 1, resets.get());
        assertEquals("the target frame rate survives the fallback", 15, applied.get(0).getFrameRate());
    }

    @Test
    public void configure_propagatesAFailureThatIsNotAboutTheCap() {
        EncoderFormat uncapped = CAPPED.withoutMaxFpsToEncoder();
        IllegalStateException failure = new IllegalStateException("codec is busy");

        assertThrows(IllegalStateException.class, () ->
                AndroidMediaCodecEncoderBridge.configureWithFallback(uncapped, format -> {
                    throw failure;
                }, () -> {
                }));
    }

    @Test
    public void uncappedFormat_hasNoCapAndKeepsEveryOtherField() {
        EncoderFormat uncapped = CAPPED.withoutMaxFpsToEncoder();

        assertEquals(0f, uncapped.getMaxFpsToEncoder(), 0f);
        assertEquals(CAPPED.getWidth(), uncapped.getWidth());
        assertEquals(CAPPED.getHeight(), uncapped.getHeight());
        assertEquals(CAPPED.getBitrateBps(), uncapped.getBitrateBps());
        assertEquals(CAPPED.getFrameRate(), uncapped.getFrameRate());
        assertEquals(CAPPED.getKeyFrameIntervalSeconds(), uncapped.getKeyFrameIntervalSeconds());
    }

    @Test
    public void zeroCap_meansNoCapWasRequested() {
        EncoderFormat format = new EncoderFormat(1280, 720, 4_000_000, 60, 2, 0f);

        assertEquals(0f, format.getMaxFpsToEncoder(), 0f);
        assertEquals(60, format.getFrameRate());
    }

    @Test
    public void nullFormat_isRejectedBeforeTouchingTheCodec() {
        assertThrows(IllegalArgumentException.class,
                () -> AndroidMediaCodecEncoderBridge.configureWithFallback(null, format -> {
                }, () -> {
                }));
    }
}
