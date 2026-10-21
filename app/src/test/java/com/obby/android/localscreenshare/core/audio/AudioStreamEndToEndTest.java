package com.obby.android.localscreenshare.core.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.obby.android.localscreenshare.core.model.AudioAccessUnit;
import com.obby.android.localscreenshare.core.model.AudioCodecCapability;
import com.obby.android.localscreenshare.core.model.AudioDescriptor;
import com.obby.android.localscreenshare.core.model.ClientSessionKey;
import com.obby.android.localscreenshare.core.model.CodecConfigPart;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.stream.ClientCapabilities;
import com.obby.android.localscreenshare.core.stream.CodecCapability;
import com.obby.android.localscreenshare.core.support.FakeAudioTransport;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * JVM end-to-end tests for the audio fan-out path: hub, per-client sessions and transports wired as
 * production wires them.
 *
 * <p><b>Assembly.</b> Three clients join the same hub with AAC capabilities; the hub publishes one
 * descriptor and a stream of 30 frames, exactly what {@code AudioPipeline} would deliver. Each
 * client is drained with {@link AudioHub#drainOne(ClientSessionKey)} until it returns false, and
 * only then are the transport events read, so the assertions describe what a client actually
 * received rather than what a session intended to send.</p>
 *
 * <p><b>Video isolation.</b> Nothing in this path references a video type, and the audio sessions
 * never close the transport (the video session owns it). The video path itself is covered by
 * {@code H264StreamEndToEndTest} and is unchanged by this batch; the "slow client" test here proves
 * that an audio queue overflow is contained in the audio session of that client.</p>
 */
public class AudioStreamEndToEndTest {

    private static final StreamKey STREAM_KEY = new StreamKey("session-audio", "stream-audio");

    private static final ClientSessionKey CLIENT_A =
            new ClientSessionKey(STREAM_KEY, "client-a");

    private static final ClientSessionKey CLIENT_B =
            new ClientSessionKey(STREAM_KEY, "client-b");

    private static final ClientSessionKey CLIENT_C =
            new ClientSessionKey(STREAM_KEY, "client-c");

    private static final int FRAME_COUNT = 30;

    private static final long FRAME_DURATION_US = 21_333L;

    @Test
    public void everyClientReceivesTheDescriptorFirstThenMonotonicFrames() throws Exception {
        AudioHub hub = new AudioHub();
        FakeAudioTransport transportA = new FakeAudioTransport();
        FakeAudioTransport transportB = new FakeAudioTransport();
        FakeAudioTransport transportC = new FakeAudioTransport();
        hub.join(CLIENT_A, audioCaps(), transportA);
        hub.join(CLIENT_B, audioCaps(), transportB);
        hub.join(CLIENT_C, audioCaps(), transportC);

        hub.onDescriptor(descriptor(1));
        for (int i = 0; i < FRAME_COUNT; i++) {
            hub.onAccessUnit(unit(1, i, i * FRAME_DURATION_US));
            // The production drain thread runs continuously; draining between offers is what keeps
            // a healthy client inside its delay bound.
            drainAll(hub, CLIENT_A);
            drainAll(hub, CLIENT_B);
            drainAll(hub, CLIENT_C);
        }

        for (ClientSessionKey key : new ClientSessionKey[]{CLIENT_A, CLIENT_B, CLIENT_C}) {
            drainAll(hub, key);
        }

        for (FakeAudioTransport transport
                : new FakeAudioTransport[]{transportA, transportB, transportC}) {
            List<FakeAudioTransport.Event> events = snapshot(transport);
            assertEquals(FRAME_COUNT + 1, events.size());
            assertTrue(events.get(0).isDescriptor());
            long previousPts = -1L;
            for (int i = 1; i < events.size(); i++) {
                FakeAudioTransport.Event event = events.get(i);
                assertTrue(event.isAccessUnit());
                assertEquals(i - 1L, event.getAccessUnit().getSequence());
                assertTrue(event.getAccessUnit().getPtsUs() > previousPts);
                previousPts = event.getAccessUnit().getPtsUs();
            }
        }
    }

    @Test
    public void aClientThatIsNotDrainedDropsItsOwnAudioOnly() throws Exception {
        AudioHub hub = new AudioHub();
        FakeAudioTransport slow = new FakeAudioTransport();
        FakeAudioTransport fast = new FakeAudioTransport();
        hub.join(CLIENT_A, audioCaps(), slow);
        hub.join(CLIENT_B, audioCaps(), fast);
        hub.onDescriptor(descriptor(1));

        for (int i = 0; i < AudioSendSession.DEFAULT_MAX_FRAMES + 8; i++) {
            hub.onAccessUnit(unit(1, i, i * FRAME_DURATION_US));
            drainAll(hub, CLIENT_B);
        }

        assertEquals(AudioSendSession.DEFAULT_MAX_FRAMES + 8, fast.getAccessUnitCount());
        assertTrue(hub.getSession(CLIENT_A).getStats().getQueuedFrames()
                < AudioSendSession.DEFAULT_MAX_FRAMES + 8);
        assertTrue(hub.getSession(CLIENT_A).getStats().getDroppedFrames() > 0);
        assertEquals(0, hub.getSession(CLIENT_B).getStats().getDroppedFrames());
        assertEquals(1, fast.getDescriptorCount());
    }

    @Test
    public void aBlockedClientDoesNotStallTheDrainOfAnother() throws Exception {
        AudioHub hub = new AudioHub();
        FakeAudioTransport blocked = new FakeAudioTransport();
        CountDownLatch release = new CountDownLatch(1);
        blocked.blockSendsUntil(release);
        FakeAudioTransport healthy = new FakeAudioTransport();
        hub.join(CLIENT_A, audioCaps(), blocked);
        hub.join(CLIENT_B, audioCaps(), healthy);
        hub.onDescriptor(descriptor(1));
        hub.onAccessUnit(unit(1, 0, 0));

        Thread blockedDrain = new Thread(() -> {
            try {
                hub.drainOne(CLIENT_A);
            } catch (IOException ignored) {
                // the test releases the latch before it times out
            }
        }, "blocked-audio-drain");
        blockedDrain.setDaemon(true);
        blockedDrain.start();

        assertTrue(blocked.awaitSendStarted(2_000L));
        drainAll(hub, CLIENT_B);

        assertEquals(1, healthy.getAccessUnitCount());
        release.countDown();
        blockedDrain.join(2_000L);
        assertFalse(blockedDrain.isAlive());
    }

    @Test
    public void anAudioTransportFailureDetachesThatClientAndIsReported() throws Exception {
        AudioHub hub = new AudioHub();
        FakeAudioTransport failing = new FakeAudioTransport();
        FakeAudioTransport healthy = new FakeAudioTransport();
        hub.join(CLIENT_A, audioCaps(), failing);
        hub.join(CLIENT_B, audioCaps(), healthy);
        hub.onDescriptor(descriptor(1));
        failing.failNextSend();

        assertThrows(IOException.class, () -> hub.drainOne(CLIENT_A));
        assertEquals(1, hub.getSessionCount());
        hub.onAccessUnit(unit(1, 0, 0));
        drainAll(hub, CLIENT_B);
        assertEquals(1, healthy.getAccessUnitCount());
    }

    @Test
    public void aClientWithoutAudioSupportNeverCreatesAnAudioSession() throws Exception {
        AudioHub hub = new AudioHub();
        assertFalse(hub.join(CLIENT_A, new ClientCapabilities(Collections.singleton("h264")),
                new FakeAudioTransport()));

        hub.onDescriptor(descriptor(1));
        hub.onAccessUnit(unit(1, 0, 0));

        assertEquals(0, hub.getSessionCount());
        assertFalse(hub.drainOne(CLIENT_A));
    }

    private static void drainAll(AudioHub hub, ClientSessionKey key) throws IOException {
        while (hub.drainOne(key)) {
            // one item at a time, like the production drain loop
        }
    }

    private static List<FakeAudioTransport.Event> snapshot(FakeAudioTransport transport) {
        synchronized (transport.events) {
            return new ArrayList<>(transport.events);
        }
    }

    private static ClientCapabilities audioCaps() {
        Map<String, CodecCapability> video =
                Collections.singletonMap("h264", CodecCapability.any("h264"));
        Map<String, AudioCodecCapability> audio =
                Collections.singletonMap("aac", AudioCodecCapability.any("aac"));
        return new ClientCapabilities(video, audio);
    }

    private static AudioDescriptor descriptor(int epoch) {
        return AudioDescriptor.builder(epoch, AudioCodecIds.AAC, AudioCodecIds.AAC_MIME, 48_000,
                        2, 128_000)
                .configParts(Collections.singletonList(
                        new CodecConfigPart(AudioCodecIds.CONFIG_PART_CSD_0,
                                new byte[]{0x12, 0x10})))
                .build();
    }

    private static AudioAccessUnit unit(int epoch, long sequence, long ptsUs) {
        return new AudioAccessUnit(epoch, sequence, ptsUs, new byte[]{0x21, 0x01});
    }
}
