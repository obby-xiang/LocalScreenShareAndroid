package com.obby.android.localscreenshare.core.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.obby.android.localscreenshare.core.model.AudioAccessUnit;
import com.obby.android.localscreenshare.core.model.AudioDescriptor;
import com.obby.android.localscreenshare.core.model.ClientSessionKey;
import com.obby.android.localscreenshare.core.model.CodecConfigPart;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.support.FakeAudioTransport;

import org.junit.Test;

import java.util.Collections;

/**
 * Unit tests for {@link AudioSendSession}.
 */
public class AudioSendSessionTest {

    private static final StreamKey STREAM_KEY = new StreamKey("session", "stream");

    private static final ClientSessionKey CLIENT =
            new ClientSessionKey(STREAM_KEY, "client-a");

    @Test
    public void descriptorIsSentBeforeTheFirstFrame() throws Exception {
        FakeAudioTransport transport = new FakeAudioTransport();
        AudioSendSession session = new AudioSendSession(CLIENT, transport);
        AudioDescriptor descriptor = descriptor(1);
        session.onDescriptor(descriptor);
        assertTrue(session.offer(unit(1, 0, 0)));

        assertTrue(session.drainOne());
        assertTrue(session.drainOne());

        assertEquals(2, transport.events.size());
        assertTrue(transport.events.get(0).isDescriptor());
        assertEquals(descriptor, transport.events.get(0).getDescriptor());
        assertTrue(transport.events.get(1).isAccessUnit());
        assertEquals(1, session.getStats().getSentFrames());
        assertEquals(1, session.getStats().getDescriptorSends());
    }

    @Test
    public void framesWithoutDescriptorAreRejected() throws Exception {
        FakeAudioTransport transport = new FakeAudioTransport();
        AudioSendSession session = new AudioSendSession(CLIENT, transport);

        assertFalse(session.offer(unit(1, 0, 0)));
        assertFalse(session.drainOne());
        assertEquals(1, session.getStats().getDroppedFrames());
    }

    @Test
    public void framesOfAnotherEpochAreRejected() throws Exception {
        FakeAudioTransport transport = new FakeAudioTransport();
        AudioSendSession session = new AudioSendSession(CLIENT, transport);
        session.onDescriptor(descriptor(2));

        assertFalse(session.offer(unit(1, 0, 0)));
        assertTrue(session.offer(unit(2, 0, 0)));
        assertEquals(1, session.getStats().getDroppedFrames());
    }

    @Test
    public void overflowDropsTheOldestFramesByCount() throws Exception {
        FakeAudioTransport transport = new FakeAudioTransport();
        AudioSendSession session = new AudioSendSession(CLIENT, transport, 2, 300L);
        session.onDescriptor(descriptor(1));

        for (int i = 0; i < 5; i++) {
            assertTrue(session.offer(unit(1, i, i * 21_333L)));
        }

        assertEquals(2, session.getStats().getQueuedFrames());
        assertEquals(3, session.getStats().getDroppedFrames());
        session.drainOne();
        session.drainOne();
        session.drainOne();
        assertEquals(2, transport.getAccessUnitCount());
        assertEquals(3L, transport.events.get(1).getAccessUnit().getSequence());
        assertEquals(4L, transport.events.get(2).getAccessUnit().getSequence());
    }

    @Test
    public void overflowDropsTheOldestFramesByDuration() throws Exception {
        FakeAudioTransport transport = new FakeAudioTransport();
        AudioSendSession session = new AudioSendSession(CLIENT, transport, 100, 50L);
        session.onDescriptor(descriptor(1));

        for (int i = 0; i < 4; i++) {
            session.offer(unit(1, i, i * 21_333L));
        }

        assertEquals(2, session.getStats().getQueuedFrames());
        assertEquals(2, session.getStats().getDroppedFrames());
    }

    @Test
    public void notReadyTransportDropsTheFrameAndCountsIt() throws Exception {
        FakeAudioTransport transport = new FakeAudioTransport();
        AudioSendSession session = new AudioSendSession(CLIENT, transport);
        session.onDescriptor(descriptor(1));
        session.offer(unit(1, 0, 0));
        assertTrue(session.drainOne());
        transport.setReady(false);

        assertFalse(session.drainOne());

        assertEquals(1, transport.getNotReadyCount());
        assertEquals(1, session.getStats().getDroppedFrames());
        assertFalse(session.isDetached());
    }

    @Test
    public void transportFailureDetachesAndRethrows() throws Exception {
        FakeAudioTransport transport = new FakeAudioTransport();
        AudioSendSession session = new AudioSendSession(CLIENT, transport);
        session.onDescriptor(descriptor(1));
        transport.failNextSend();

        assertThrows(java.io.IOException.class, session::drainOne);
        assertTrue(session.isDetached());
        assertFalse(session.offer(unit(1, 0, 0)));
        assertFalse(session.drainOne());
    }

    @Test
    public void detachClearsTheQueueAndMakesEverythingNoOp() {
        FakeAudioTransport transport = new FakeAudioTransport();
        AudioSendSession session = new AudioSendSession(CLIENT, transport);
        session.onDescriptor(descriptor(1));
        session.offer(unit(1, 0, 0));
        session.detach();
        session.detach();

        assertFalse(session.offer(unit(1, 1, 21_333L)));
        assertEquals(0, session.getStats().getQueuedFrames());
    }

    @Test
    public void descriptorEpochRulesMatchTheVideoSession() {
        FakeAudioTransport transport = new FakeAudioTransport();
        AudioSendSession session = new AudioSendSession(CLIENT, transport);
        AudioDescriptor first = descriptor(1);

        assertTrue(session.onDescriptor(first));
        assertFalse(session.onDescriptor(first));
        assertTrue(session.onDescriptor(first.withConfigEpoch(3)));
        assertFalse(session.onDescriptor(first.withConfigEpoch(2)));
        assertThrows(IllegalArgumentException.class,
                () -> session.onDescriptor(otherFormat(3)));
        assertEquals(3, session.getCurrentDescriptor().getConfigEpoch());
    }

    @Test
    public void newerEpochDiscardsQueuedFramesOfTheOldEpoch() {
        FakeAudioTransport transport = new FakeAudioTransport();
        AudioSendSession session = new AudioSendSession(CLIENT, transport);
        session.onDescriptor(descriptor(1));
        session.offer(unit(1, 0, 0));

        session.onDescriptor(descriptor(2));

        assertEquals(0, session.getStats().getQueuedFrames());
        assertEquals(1, session.getStats().getDroppedFrames());
    }

    @Test
    public void boundsAndArgumentsAreValidated() {
        FakeAudioTransport transport = new FakeAudioTransport();
        assertThrows(IllegalArgumentException.class,
                () -> new AudioSendSession(null, transport));
        assertThrows(IllegalArgumentException.class,
                () -> new AudioSendSession(CLIENT, null));
        assertThrows(IllegalArgumentException.class,
                () -> new AudioSendSession(CLIENT, transport, 0, 300L));
        assertThrows(IllegalArgumentException.class,
                () -> new AudioSendSession(CLIENT, transport, 32, 0L));
        AudioSendSession session = new AudioSendSession(CLIENT, transport);
        assertThrows(IllegalArgumentException.class, () -> session.offer(null));
    }

    private static AudioDescriptor descriptor(int epoch) {
        return AudioDescriptor.builder(epoch, AudioCodecIds.AAC, AudioCodecIds.AAC_MIME, 48_000,
                        2, 128_000)
                .configParts(Collections.singletonList(
                        new CodecConfigPart(AudioCodecIds.CONFIG_PART_CSD_0,
                                new byte[]{0x12, 0x10})))
                .build();
    }

    private static AudioDescriptor otherFormat(int epoch) {
        return AudioDescriptor.builder(epoch, AudioCodecIds.AAC, AudioCodecIds.AAC_MIME, 44_100,
                        1, 64_000)
                .configParts(Collections.singletonList(
                        new CodecConfigPart(AudioCodecIds.CONFIG_PART_CSD_0,
                                new byte[]{0x12, 0x08})))
                .build();
    }

    private static AudioAccessUnit unit(int epoch, long sequence, long ptsUs) {
        return new AudioAccessUnit(epoch, sequence, ptsUs, new byte[]{0x21, 0x01});
    }
}
