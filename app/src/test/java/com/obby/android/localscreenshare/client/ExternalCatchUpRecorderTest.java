package com.obby.android.localscreenshare.client;

import android.view.Surface;

import com.obby.android.localscreenshare.core.client.CatchUpSampler;
import com.obby.android.localscreenshare.core.client.ClientMediaSession;
import com.obby.android.localscreenshare.core.codec.CodecIds;
import com.obby.android.localscreenshare.core.codec.CodecRegistration;
import com.obby.android.localscreenshare.core.codec.CodecRegistry;
import com.obby.android.localscreenshare.core.codec.h264.H264Codec;
import com.obby.android.localscreenshare.core.metrics.PipelineMetrics;
import com.obby.android.localscreenshare.core.model.AccessUnitFlags;
import com.obby.android.localscreenshare.core.model.BitstreamFormat;
import com.obby.android.localscreenshare.core.model.ClientSessionKey;
import com.obby.android.localscreenshare.core.model.CodecConfigPart;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.JoinInfo;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.render.EncodedImageFrame;
import com.obby.android.localscreenshare.core.render.RenderTarget;
import com.obby.android.localscreenshare.core.stream.ClientCapabilities;
import com.obby.android.localscreenshare.core.support.FakeMediaCodecBridgeFactory;
import com.obby.android.localscreenshare.core.support.H264Fixtures;
import com.obby.android.localscreenshare.core.support.MutableNanoClock;
import com.obby.android.localscreenshare.core.support.TestH264Registration;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests the accounting of a whole-unit loss on the viewer's decode thread.
 *
 * <p>A loss is only a catch-up if there is a decoder to resynchronize: before the first
 * descriptor and after the session was closed the accounting must stay untouched. The episode
 * must also start at the moment the loss was detected, not when the queued accounting task got
 * to run, otherwise a slow decode queue would make {@code rx.catchup.lat} look shorter.</p>
 */
public class ExternalCatchUpRecorderTest {

    private static final StreamKey STREAM_KEY = new StreamKey("session-9", "stream-9");

    private static final ClientSessionKey CLIENT_KEY =
            new ClientSessionKey(STREAM_KEY, "client-9");

    private static final int WIDTH = 640;

    private static final int HEIGHT = 480;

    private static final long DETECTION_NS = 1_000_000_000L;

    private static final long QUEUE_DELAY_NS = 2_000_000_000L;

    private static final long JOIN_DELAY_NS = 500_000_000L;

    private final MutableNanoClock clock = new MutableNanoClock();

    private final PipelineMetrics metrics = PipelineMetrics.create(clock, 8);

    private final CatchUpSampler sampler = new CatchUpSampler();

    private final AtomicInteger generation = new AtomicInteger();

    private final ExternalCatchUpRecorder recorder =
            new ExternalCatchUpRecorder(sampler, metrics, generation);

    private final QueueingExecutor executor = new QueueingExecutor();

    @Test
    public void submit_beforeTheFirstDescriptor_recordsNothing() {
        ClientMediaSession session = newSession(new FakeMediaCodecBridgeFactory());

        recorder.submit(executor, session, () -> false);
        clock.advance(QUEUE_DELAY_NS);
        executor.runAll();

        assertNothingRecorded();
    }

    @Test
    public void submit_afterClose_recordsNothing() {
        ClientMediaSession session = newSession(new FakeMediaCodecBridgeFactory());
        assertTrue(session.onDescriptor(h264Descriptor(2)));
        assertTrue(session.onAccessUnit(joinAu(2, 0)));
        session.close();

        recorder.submit(executor, session, () -> false);
        clock.advance(QUEUE_DELAY_NS);
        executor.runAll();

        assertNothingRecorded();
    }

    @Test
    public void submit_afterStop_recordsNothing() {
        ClientMediaSession session = newSession(new FakeMediaCodecBridgeFactory());
        assertTrue(session.onDescriptor(h264Descriptor(2)));

        recorder.submit(executor, session, () -> true);
        executor.runAll();

        assertNothingRecorded();
    }

    @Test
    public void submit_startsTheEpisodeAtDetectionTimeNotAtTaskRunTime() {
        ClientMediaSession session = newSession(new FakeMediaCodecBridgeFactory());
        assertTrue(session.onDescriptor(h264Descriptor(2)));
        clock.set(DETECTION_NS);

        recorder.submit(executor, session, () -> false);
        // The decode thread is busy with a frame: the accounting task waits behind it.
        clock.advance(QUEUE_DELAY_NS);
        executor.runAll();

        assertEquals(1L, metrics.count(PipelineMetrics.Counter.CATCH_UP_EPISODES));
        assertEquals(PipelineMetrics.CATCH_UP_REASON_EXTERNAL,
                metrics.getGauge(PipelineMetrics.Gauge.CATCH_UP_REASON));
        assertTrue(sampler.isCatchingUp());
        assertEquals(DETECTION_NS, sampler.getEpisodeStartedNs());

        // The join point arrives after the queue delay; the recovery time includes it instead of
        // being shortened to the time the accounting task happened to run.
        long joinAtNs = clock.nowNs() + JOIN_DELAY_NS;
        long episodeStartedNs = sampler.getEpisodeStartedNs();
        sampler.onDecoded(joinAtNs, 4, 20_000_000L, true);

        assertFalse(sampler.isCatchingUp());
        // Detection at 1 s, join at 3.5 s: the 2 s queue delay is part of the episode.
        assertEquals(QUEUE_DELAY_NS + JOIN_DELAY_NS, joinAtNs - episodeStartedNs);
    }

    private void assertNothingRecorded() {
        assertEquals(0L, metrics.count(PipelineMetrics.Counter.CATCH_UP_EPISODES));
        assertEquals(0L, metrics.getGauge(PipelineMetrics.Gauge.CATCH_UP_REASON));
        assertEquals(0, generation.get());
        assertFalse(sampler.isCatchingUp());
    }

    private ClientMediaSession newSession(FakeMediaCodecBridgeFactory bridges) {
        CodecRegistration registration = TestH264Registration.withoutSurfaceRequirement(bridges);
        CodecRegistry registry = new CodecRegistry();
        registry.register(registration);
        return new ClientMediaSession(CLIENT_KEY, registry,
                new ClientCapabilities(Collections.singleton(CodecIds.H264)),
                new NoSurfaceRenderTarget(), null, PipelineMetrics.NONE);
    }

    private static StreamDescriptor h264Descriptor(int configEpoch) {
        return StreamDescriptor.builder(STREAM_KEY, configEpoch, "h264", H264Codec.MIME,
                        BitstreamFormat.ANNEX_B)
                .codedSize(WIDTH, HEIGHT)
                .visibleSize(WIDTH, HEIGHT)
                .configParts(Arrays.asList(
                        new CodecConfigPart(H264Codec.CONFIG_PART_SPS, H264Fixtures.sps()),
                        new CodecConfigPart(H264Codec.CONFIG_PART_PPS, H264Fixtures.pps())))
                .build();
    }

    private static EncodedAccessUnit joinAu(int configEpoch, long sequence) {
        return new EncodedAccessUnit(STREAM_KEY, configEpoch, sequence, sequence * 1000L,
                EncodedAccessUnit.DTS_UNSET, AccessUnitFlags.of(true, true, false),
                new JoinInfo(configEpoch, Collections.emptyList()), null,
                H264Fixtures.annexB(H264Fixtures.idrSlice()));
    }

    /**
     * Executor that holds tasks until the test runs them, so queue delay is explicit.
     */
    private static final class QueueingExecutor implements Executor {

        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        void runAll() {
            List<Runnable> queued = new ArrayList<>(tasks);
            tasks.clear();
            for (Runnable task : queued) {
                task.run();
            }
        }
    }

    private static final class NoSurfaceRenderTarget implements RenderTarget {

        @Override
        public Surface getSurface() {
            return null;
        }

        @Override
        public void setContentSize(int width, int height) {
        }

        @Override
        public void renderEncodedImage(EncodedImageFrame frame) {
        }
    }
}
