package com.obby.android.localscreenshare.core.codec.mediacodec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Unit tests for the optional-key fallback ladder of {@link AndroidMediaCodecDecoderBridge}.
 *
 * <p>The ladder is driven through {@link AndroidMediaCodecDecoderBridge.FormatApplier}, which
 * carries booleans only, so no {@code MediaFormat} or {@code MediaCodec} is created and the test
 * runs on the JVM. Every case pins both the surviving state and the exact sequence of attempts,
 * because a fallback that applies the wrong keys looks identical to a correct one from the state
 * alone.</p>
 */
public class AndroidMediaCodecDecoderBridgeTest {

    @Test
    public void configure_appliesBothKeysWhenAccepted() {
        Recorder recorder = new Recorder();

        LowLatencyState state = AndroidMediaCodecDecoderBridge.configureWithFallback(true,
                recorder, recorder::reset);

        assertEquals(LowLatencyState.APPLIED, state);
        assertEquals(Arrays.asList("true,true"), recorder.calls);
        assertEquals(0, recorder.resetCount);
    }

    @Test
    public void configure_retriesWithoutPriorityWhenOnlyPriorityRejected() {
        Recorder recorder = new Recorder();
        recorder.failOnce("true,true");

        LowLatencyState state = AndroidMediaCodecDecoderBridge.configureWithFallback(true,
                recorder, recorder::reset);

        assertEquals(LowLatencyState.APPLIED_WITHOUT_PRIORITY, state);
        assertEquals(Arrays.asList("true,true", "true,false"), recorder.calls);
        assertEquals(1, recorder.resetCount);
    }

    @Test
    public void configure_fallsBackWithoutOptionalKeysWhenLowLatencyRejected() {
        Recorder recorder = new Recorder();
        recorder.failOnce("true,true");
        recorder.failOnce("true,false");

        LowLatencyState state = AndroidMediaCodecDecoderBridge.configureWithFallback(true,
                recorder, recorder::reset);

        assertEquals(LowLatencyState.REJECTED, state);
        assertEquals(Arrays.asList("true,true", "true,false", "false,false"), recorder.calls);
        assertEquals(2, recorder.resetCount);
    }

    @Test
    public void configure_rethrowsWhenTheFinalAttemptAlsoFails() {
        Recorder recorder = new Recorder();
        recorder.failAlways(new IllegalStateException("codec rejected"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> AndroidMediaCodecDecoderBridge.configureWithFallback(true, recorder,
                        recorder::reset));

        assertEquals("codec rejected", failure.getMessage());
        assertEquals(Arrays.asList("true,true", "true,false", "false,false"), recorder.calls);
        assertEquals(2, recorder.resetCount);
    }

    @Test
    public void configure_unrelatedFailureKeepsOriginalType() {
        Recorder recorder = new Recorder();
        IllegalArgumentException failure = new IllegalArgumentException("bad format");
        recorder.failAlways(failure);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> AndroidMediaCodecDecoderBridge.configureWithFallback(false, recorder,
                        recorder::reset));

        assertSame(failure, thrown);
        assertEquals(Arrays.asList("false,false"), recorder.calls);
        assertEquals(0, recorder.resetCount);
    }

    @Test
    public void configure_notRequestedNeverSendsOptionalKeys() {
        Recorder recorder = new Recorder();

        LowLatencyState state = AndroidMediaCodecDecoderBridge.configureWithFallback(false,
                recorder, recorder::reset);

        assertEquals(LowLatencyState.NOT_REQUESTED, state);
        assertEquals(Arrays.asList("false,false"), recorder.calls);
        assertEquals(0, recorder.resetCount);
    }

    /**
     * Applier that records every attempt and fails exactly the attempts a test names.
     */
    private static final class Recorder implements AndroidMediaCodecDecoderBridge.FormatApplier {

        private final List<String> calls = new ArrayList<>();

        private final List<String> failingCalls = new ArrayList<>();

        private RuntimeException alwaysFail;

        private int resetCount;

        void failOnce(String call) {
            failingCalls.add(call);
        }

        void failAlways(RuntimeException failure) {
            alwaysFail = failure;
        }

        @Override
        public void configure(boolean lowLatency, boolean priority) {
            String call = lowLatency + "," + priority;
            calls.add(call);
            if (alwaysFail != null) {
                throw alwaysFail;
            }
            if (failingCalls.remove(call)) {
                throw new IllegalArgumentException("rejected " + call);
            }
        }

        void reset() {
            resetCount++;
        }
    }
}
