package com.obby.android.localscreenshare.core.stream;

import com.obby.android.localscreenshare.core.codec.CodecRegistry;
import com.obby.android.localscreenshare.core.model.BitstreamFormat;
import com.obby.android.localscreenshare.core.model.ClientSessionKey;
import com.obby.android.localscreenshare.core.model.ClientState;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.model.StreamParameters;
import com.obby.android.localscreenshare.core.net.MediaTransportKind;
import com.obby.android.localscreenshare.core.support.FakeCodec;
import com.obby.android.localscreenshare.core.support.FakeTransport;
import com.obby.android.localscreenshare.core.support.MutableClock;

import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the current-session transport swap of {@link StreamHub}.
 *
 * <p>The swap is how a handler hands a session a new media channel: it must only apply while the
 * key still maps to that session, and the session must re-send its descriptor on the new channel
 * before any access unit.</p>
 */
public class StreamHubTransportSwapTest {

    private static final StreamKey STREAM_KEY = new StreamKey("session-1", "stream-1");

    private static final ClientSessionKey CLIENT_A = new ClientSessionKey(STREAM_KEY, "client-A");

    private static final ClientSessionKey CLIENT_B = new ClientSessionKey(STREAM_KEY, "client-B");

    private static final String CODEC_ID = FakeCodec.DEFAULT_CODEC_ID;

    private static final long JOIN_INTERVAL_MS = 100L;

    private static final long MAX_AGE_MS = 5_000L;

    @Test
    public void swapTransportIfCurrent_handsTheNewChannelToTheSession() throws Exception {
        FakeCodec codec = new FakeCodec(CODEC_ID, 0, false, false);
        codec.setAutoEmitJoinOnRequest(false);
        StreamHub hub = newHub(registry(codec));
        FakeTransport first = new FakeTransport();
        hub.join(CLIENT_A, capabilities(), first);
        codec.emitJoin(new byte[]{0x21});
        drainToStreaming(hub, CLIENT_A, first);
        ClientSendSession session = hub.getSession(CLIENT_A);

        FakeTransport second = new FakeTransport();
        assertSame(first, hub.swapTransportIfCurrent(CLIENT_A, session, second));
        assertEquals(ClientState.WAIT_RANDOM_ACCESS, session.getState());

        assertTrue(hub.drainOne(CLIENT_A));
        assertEquals(1, second.getEvents().size());
        assertTrue(second.getEvents().get(0).isDescriptor());
        assertEquals(0L, session.getStats().getQueuedUnits());

        codec.emitJoin(new byte[]{0x22});
        assertTrue(hub.drainOne(CLIENT_A));
        assertEquals(2, second.getEvents().size());
        assertTrue(second.getEvents().get(1).isAccessUnit());
        assertEquals(ClientState.STREAMING, session.getState());
    }

    @Test
    public void swapTransportIfCurrent_ignoresAReplacedSession() throws Exception {
        FakeCodec codec = new FakeCodec(CODEC_ID, 0, false, false);
        StreamHub hub = newHub(registry(codec));
        hub.join(CLIENT_A, capabilities(), new FakeTransport());
        ClientSendSession replaced = hub.getSession(CLIENT_A);
        FakeTransport takeover = new FakeTransport();
        hub.join(CLIENT_A, capabilities(), takeover);
        assertNotSame(replaced, hub.getSession(CLIENT_A));

        FakeTransport late = new FakeTransport();
        assertNull(hub.swapTransportIfCurrent(CLIENT_A, replaced, late));

        assertTrue(late.getEvents().isEmpty());
        assertFalse(late.isClosed());
        assertTrue(replaced.isDetached());
        assertFalse(hub.getSession(CLIENT_A).isDetached());
    }

    @Test
    public void swapTransportIfCurrent_ignoresAKeyThatMapsToAnotherSession() throws Exception {
        FakeCodec codec = new FakeCodec(CODEC_ID, 0, false, false);
        StreamHub hub = newHub(registry(codec));
        hub.join(CLIENT_A, capabilities(), new FakeTransport());
        ClientSendSession session = hub.getSession(CLIENT_A);

        FakeTransport late = new FakeTransport();
        assertNull(hub.swapTransportIfCurrent(CLIENT_B, session, late));

        assertTrue(late.getEvents().isEmpty());
        assertFalse(session.isDetached());
    }

    @Test
    public void swapTransportIfCurrent_rejectsNullArguments() throws Exception {
        FakeCodec codec = new FakeCodec(CODEC_ID, 0, false, false);
        StreamHub hub = newHub(registry(codec));
        hub.join(CLIENT_A, capabilities(), new FakeTransport());
        ClientSendSession session = hub.getSession(CLIENT_A);

        assertNull(hub.swapTransportIfCurrent(null, session, new FakeTransport()));
        assertNull(hub.swapTransportIfCurrent(CLIENT_A, null, new FakeTransport()));
        assertNull(hub.swapTransportIfCurrent(CLIENT_A, session, null));
        assertFalse(session.isDetached());
    }

    @Test
    public void joinWithAChannel_recordsTheChannel() throws Exception {
        FakeCodec codec = new FakeCodec(CODEC_ID, 0, false, false);
        StreamHub hub = newHub(registry(codec));
        FakeTransport transport = new FakeTransport();

        assertEquals(JoinDecision.Status.ACCEPTED, hub.join(CLIENT_A, capabilities(), transport,
                MediaTransportKind.TCP, null).getStatus());

        ClientSendSession session = hub.getSession(CLIENT_A);
        assertEquals(MediaTransportKind.TCP, session.getTransportKind());
        assertEquals("tcp", session.snapshot().getChannel());
    }

    @Test
    public void joinWithARecovery_recoversAFailedSendWithoutDetaching() throws Exception {
        FakeCodec codec = new FakeCodec(CODEC_ID, 0, false, false);
        codec.setAutoEmitJoinOnRequest(false);
        StreamHub hub = newHub(registry(codec));
        FakeTransport carrier = new FakeTransport();
        FakeTransport channel = new FakeTransport();
        hub.join(CLIENT_A, capabilities(), channel, MediaTransportKind.TCP,
                (failed, cause) -> carrier);
        codec.emitJoin(new byte[]{0x41});
        assertTrue(hub.drainOne(CLIENT_A));
        assertTrue(hub.drainOne(CLIENT_A));

        channel.failNextSend();
        codec.emitJoin(new byte[]{0x42});

        assertFalse(hub.drainOne(CLIENT_A));
        assertFalse(hub.getSession(CLIENT_A).isDetached());
        assertTrue(channel.isClosed());

        assertTrue(hub.drainOne(CLIENT_A));
        assertTrue(carrier.getEvents().get(0).isDescriptor());
        assertEquals(ClientState.WAIT_RANDOM_ACCESS, hub.getSession(CLIENT_A).getState());

        codec.emitJoin(new byte[]{0x43});
        assertTrue(hub.drainOne(CLIENT_A));
        assertEquals(ClientState.STREAMING, hub.getSession(CLIENT_A).getState());
    }

    private static StreamHub newHub(CodecRegistry registry) {
        MutableClock clock = new MutableClock();
        StreamRuntime runtime = new StreamRuntime(registry, STREAM_KEY,
                StreamDescriptor.builder(STREAM_KEY, 1, CODEC_ID, FakeCodec.DEFAULT_MIME,
                                BitstreamFormat.ANNEX_B)
                        .codedSize(64, 48)
                        .visibleSize(64, 48)
                        .build(),
                StreamParameters.DEFAULT, clock, JOIN_INTERVAL_MS);
        runtime.start();
        StreamHub hub = new StreamHub(registry, new StreamQueueLimits(128L, 2, MAX_AGE_MS), clock);
        hub.register(runtime);
        hub.activate(STREAM_KEY);
        return hub;
    }

    private static CodecRegistry registry(FakeCodec codec) {
        CodecRegistry registry = new CodecRegistry();
        registry.register(codec.registration());
        return registry;
    }

    private static ClientCapabilities capabilities() {
        return new ClientCapabilities(new HashSet<>(Arrays.asList(CODEC_ID)));
    }

    private static void drainToStreaming(StreamHub hub, ClientSessionKey key,
                                         FakeTransport transport) throws IOException {
        assertTrue(hub.drainOne(key));
        assertTrue(hub.drainOne(key));
        assertEquals(ClientState.STREAMING, hub.getSession(key).getState());
        assertEquals(2, transport.getEvents().size());
        assertTrue(transport.getEvents().get(0).isDescriptor());
        assertTrue(transport.getEvents().get(1).isAccessUnit());
    }
}
