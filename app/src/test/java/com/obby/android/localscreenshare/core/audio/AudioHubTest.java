package com.obby.android.localscreenshare.core.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
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
import java.util.Collections;
import java.util.Map;

/**
 * Unit tests for {@link AudioHub}.
 */
public class AudioHubTest {

    private static final StreamKey STREAM_KEY = new StreamKey("session", "stream");

    private static final ClientSessionKey CLIENT_A =
            new ClientSessionKey(STREAM_KEY, "client-a");

    private static final ClientSessionKey CLIENT_B =
            new ClientSessionKey(STREAM_KEY, "client-b");

    @Test
    public void joinRefusesAClientWithoutAnAudioDecoder() {
        AudioHub hub = new AudioHub();
        FakeAudioTransport transport = new FakeAudioTransport();

        assertFalse(hub.join(CLIENT_A, videoOnlyCaps(), transport));
        assertEquals(0, hub.getSessionCount());
        assertNull(hub.getSession(CLIENT_A));
    }

    @Test
    public void joinQueuesTheDescriptorPublishedBeforeTheJoin() throws Exception {
        AudioHub hub = new AudioHub();
        hub.onDescriptor(descriptor(1));
        FakeAudioTransport transport = new FakeAudioTransport();

        assertTrue(hub.join(CLIENT_A, audioCaps(), transport));
        assertTrue(hub.drainOne(CLIENT_A));

        assertTrue(transport.events.get(0).isDescriptor());
        assertNotNull(hub.getDescriptor());
    }

    @Test
    public void joinWithoutADescriptorSendsNothing() throws Exception {
        AudioHub hub = new AudioHub();
        FakeAudioTransport transport = new FakeAudioTransport();

        assertTrue(hub.join(CLIENT_A, audioCaps(), transport));
        assertFalse(hub.drainOne(CLIENT_A));
        assertTrue(transport.events.isEmpty());
    }

    @Test
    public void aFailingTransportIsRemovedWithoutAffectingOtherClients() throws Exception {
        AudioHub hub = new AudioHub();
        hub.onDescriptor(descriptor(1));
        FakeAudioTransport failing = new FakeAudioTransport();
        FakeAudioTransport healthy = new FakeAudioTransport();
        hub.join(CLIENT_A, audioCaps(), failing);
        hub.join(CLIENT_B, audioCaps(), healthy);
        failing.failNextSend();

        assertThrows(IOException.class, () -> hub.drainOne(CLIENT_A));
        assertEquals(1, hub.getSessionCount());
        hub.onAccessUnit(unit(1, 0, 0));
        assertTrue(hub.drainOne(CLIENT_B));
    }

    @Test
    public void takeoverReplacesOnlyThatClient() throws Exception {
        AudioHub hub = new AudioHub();
        hub.onDescriptor(descriptor(1));
        FakeAudioTransport first = new FakeAudioTransport();
        FakeAudioTransport second = new FakeAudioTransport();
        FakeAudioTransport other = new FakeAudioTransport();
        hub.join(CLIENT_A, audioCaps(), first);
        AudioSendSession firstSession = hub.getSession(CLIENT_A);
        hub.join(CLIENT_B, audioCaps(), other);

        assertTrue(hub.join(CLIENT_A, audioCaps(), second));

        assertTrue(firstSession.isDetached());
        assertEquals(2, hub.getSessionCount());
        assertTrue(hub.drainOne(CLIENT_A));
        assertTrue(second.events.get(0).isDescriptor());
        assertTrue(first.events.isEmpty());
        assertTrue(hub.drainOne(CLIENT_B));
    }

    @Test
    public void detachIfCurrentDoesNotRemoveTheReplacement() {
        AudioHub hub = new AudioHub();
        FakeAudioTransport first = new FakeAudioTransport();
        FakeAudioTransport second = new FakeAudioTransport();
        hub.join(CLIENT_A, audioCaps(), first);
        AudioSendSession old = hub.getSession(CLIENT_A);
        hub.join(CLIENT_A, audioCaps(), second);

        hub.detachIfCurrent(CLIENT_A, old);

        assertNotNull(hub.getSession(CLIENT_A));
        assertFalse(hub.getSession(CLIENT_A).isDetached());
    }

    @Test
    public void broadcastIgnoresFramesOfAnotherEpoch() throws Exception {
        AudioHub hub = new AudioHub();
        hub.onDescriptor(descriptor(2));
        FakeAudioTransport transport = new FakeAudioTransport();
        hub.join(CLIENT_A, audioCaps(), transport);

        hub.onAccessUnit(unit(1, 0, 0));
        hub.onAccessUnit(unit(2, 0, 0));

        hub.drainOne(CLIENT_A);
        hub.drainOne(CLIENT_A);
        assertEquals(1, transport.getAccessUnitCount());
        assertFalse(hub.drainOne(CLIENT_A));
    }

    @Test
    public void descriptorEpochRulesAreAppliedAtTheHub() {
        AudioHub hub = new AudioHub();
        hub.onDescriptor(descriptor(2));

        hub.onDescriptor(descriptor(1));

        assertEquals(2, hub.getDescriptor().getConfigEpoch());
    }

    @Test
    public void aggregateStatsSumEverySession() throws Exception {
        AudioHub hub = new AudioHub();
        hub.onDescriptor(descriptor(1));
        hub.join(CLIENT_A, audioCaps(), new FakeAudioTransport());
        hub.join(CLIENT_B, audioCaps(), new FakeAudioTransport());
        hub.onAccessUnit(unit(1, 0, 0));

        AudioSendStats stats = hub.getAggregateStats();

        assertEquals(2, stats.getQueuedFrames());
        assertEquals(0, stats.getSentFrames());
        assertSame(AudioSendStats.EMPTY, AudioSendStats.EMPTY.plus(null));
    }

    @Test
    public void argumentsAreValidated() throws Exception {
        AudioHub hub = new AudioHub();
        FakeAudioTransport transport = new FakeAudioTransport();

        assertThrows(IllegalArgumentException.class, () -> hub.join(null, audioCaps(), transport));
        assertThrows(IllegalArgumentException.class, () -> hub.join(CLIENT_A, null, transport));
        assertThrows(IllegalArgumentException.class, () -> hub.join(CLIENT_A, audioCaps(), null));
        assertFalse(hub.drainOne(null));
        hub.detach(null);
        hub.onDescriptor(null);
        hub.onAccessUnit(null);
    }

    private static ClientCapabilities audioCaps() {
        Map<String, CodecCapability> video =
                Collections.singletonMap("h264", CodecCapability.any("h264"));
        Map<String, AudioCodecCapability> audio =
                Collections.singletonMap("aac", AudioCodecCapability.any("aac"));
        return new ClientCapabilities(video, audio);
    }

    private static ClientCapabilities videoOnlyCaps() {
        return new ClientCapabilities(Collections.singleton("h264"));
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
