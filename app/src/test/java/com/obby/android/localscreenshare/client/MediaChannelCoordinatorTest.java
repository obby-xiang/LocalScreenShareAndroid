package com.obby.android.localscreenshare.client;

import com.obby.android.localscreenshare.core.net.MediaChannelLog;
import com.obby.android.localscreenshare.core.net.MediaChannelOffer;
import com.obby.android.localscreenshare.core.net.MediaChannelStatusListener;
import com.obby.android.localscreenshare.core.net.MediaTransportKind;
import com.obby.android.localscreenshare.grpc.screenstream.MediaChannelEvent;
import com.obby.android.localscreenshare.grpc.screenstream.MediaChannelEventType;
import com.obby.android.localscreenshare.grpc.screenstream.MediaClose;
import com.obby.android.localscreenshare.grpc.screenstream.MediaOffer;
import com.obby.android.localscreenshare.transport.TcpMediaReceiver;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link MediaChannelCoordinator}.
 *
 * <p>The coordinator owns the answer side of the negotiation, so the tests assert exactly one
 * answer per channel: {@code READY} when the link comes up, {@code FAILED} when it never does,
 * {@code CLOSED} when it was up and then ended, and a refusal with a reason for an offer this build
 * cannot serve. Links are faked, so no socket is opened here.</p>
 */
public class MediaChannelCoordinatorTest {

    private static final byte[] CHANNEL_ID = bytes(16, 0x11);

    @Test
    public void offer_opensTheChannelAndAnswersReady() throws Exception {
        RecordingSink sink = new RecordingSink();
        FakeLink link = new FakeLink(true, null);
        MediaChannelCoordinator coordinator = coordinator(sink, link);

        coordinator.onOffer(offer(CHANNEL_ID, "tcp"));

        assertEquals(1, sink.events.size());
        assertEquals(MediaChannelEventType.READY, sink.events.get(0).getType());
        assertArrayEquals(CHANNEL_ID, sink.events.get(0).getChannelId().toByteArray());
        assertTrue(link.awaitStarted());
        assertEquals(hexOf(CHANNEL_ID), coordinator.getOpenChannelIdHex());
        assertNull(coordinator.getLastFailureReason());
    }

    @Test
    public void channelFailureBeforeReady_answersFailedOnce() {
        RecordingSink sink = new RecordingSink();
        FakeLink link = new FakeLink(false, "tls_failed");
        MediaChannelCoordinator coordinator = coordinator(sink, link);

        coordinator.onOffer(offer(CHANNEL_ID, "tcp"));

        assertEquals(1, sink.events.size());
        assertEquals(MediaChannelEventType.FAILED, sink.events.get(0).getType());
        assertEquals("tls_failed", sink.events.get(0).getReason());
        assertEquals("tls_failed", coordinator.getLastFailureReason());
    }

    @Test
    public void channelFailureAfterReady_answersClosedOnce() {
        RecordingSink sink = new RecordingSink();
        FakeLink link = new FakeLink(true, "channel_closed");
        MediaChannelCoordinator coordinator = coordinator(sink, link);

        coordinator.onOffer(offer(CHANNEL_ID, "tcp"));

        assertEquals(2, sink.events.size());
        assertEquals(MediaChannelEventType.READY, sink.events.get(0).getType());
        assertEquals(MediaChannelEventType.CLOSED, sink.events.get(1).getType());
        assertEquals("channel_closed", sink.events.get(1).getReason());
    }

    @Test
    public void unsupportedTransport_isRefusedWithAReason() {
        RecordingSink sink = new RecordingSink();
        MediaChannelCoordinator coordinator = coordinator(sink, new FakeLink(true, null));

        coordinator.onOffer(offer(CHANNEL_ID, "quic"));

        assertEquals(1, sink.events.size());
        assertEquals(MediaChannelEventType.FAILED, sink.events.get(0).getType());
        assertEquals(MediaChannelCoordinator.REASON_UNSUPPORTED, sink.events.get(0).getReason());
    }

    @Test
    public void offerWithoutAHost_isOpenedOnTheControlPlaneAddress() throws Exception {
        RecordingSink sink = new RecordingSink();
        FakeLink link = new FakeLink(true, null);
        MediaChannelCoordinator coordinator = coordinator(sink, link);

        // An empty host is the server saying "the address you already reached me on"; the port is
        // the only thing that decides whether a channel can be opened at all.
        coordinator.onOffer(MediaOffer.newBuilder()
                .setChannelId(com.google.protobuf.ByteString.copyFrom(CHANNEL_ID))
                .setTransport("tcp")
                .setPort(9100)
                .build());

        assertTrue(link.awaitStarted());
        assertEquals(1, sink.events.size());
        assertEquals(MediaChannelEventType.READY, sink.events.get(0).getType());
        assertNull(coordinator.getLastFailureReason());
    }

    @Test
    public void offerWithoutAnEndpoint_isRefusedWithAReason() {
        RecordingSink sink = new RecordingSink();
        MediaChannelCoordinator coordinator = coordinator(sink, new FakeLink(true, null));
        MediaOffer offer = MediaOffer.newBuilder()
                .setChannelId(com.google.protobuf.ByteString.copyFrom(CHANNEL_ID))
                .setTransport("tcp")
                .build();

        coordinator.onOffer(offer);

        assertEquals(1, sink.events.size());
        assertEquals(MediaChannelCoordinator.REASON_NO_ENDPOINT, sink.events.get(0).getReason());
    }

    @Test
    public void missingIdentity_declinesTheChannel() {
        RecordingSink sink = new RecordingSink();
        MediaChannelCoordinator coordinator = new MediaChannelCoordinator(sink, Runnable::run,
                (offer, status) -> null);

        coordinator.onOffer(offer(CHANNEL_ID, "tcp"));

        assertEquals(1, sink.events.size());
        assertEquals(MediaChannelCoordinator.REASON_NO_IDENTITY, sink.events.get(0).getReason());
    }

    @Test
    public void repeatedOffer_isAnsweredOnce() {
        RecordingSink sink = new RecordingSink();
        MediaChannelCoordinator coordinator = coordinator(sink, new FakeLink(true, null));

        coordinator.onOffer(offer(CHANNEL_ID, "tcp"));
        coordinator.onOffer(offer(CHANNEL_ID, "tcp"));

        assertEquals(1, sink.events.size());
    }

    @Test
    public void offerForAClosedChannel_isIgnored() {
        RecordingSink sink = new RecordingSink();
        MediaChannelCoordinator coordinator = coordinator(sink, new FakeLink(true, null));
        coordinator.onOffer(offer(CHANNEL_ID, "tcp"));
        coordinator.onMediaClose(close(CHANNEL_ID));

        coordinator.onOffer(offer(CHANNEL_ID, "tcp"));

        assertEquals(1, sink.events.size());
        assertNull(coordinator.getOpenChannelIdHex());
    }

    @Test
    public void close_releasesTheLinkAndIsIdempotent() throws Exception {
        RecordingSink sink = new RecordingSink();
        FakeLink link = new FakeLink(true, null);
        link.block();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            MediaChannelCoordinator coordinator = new MediaChannelCoordinator(sink, executor,
                    (offer, status) -> {
                        link.attach(status);
                        return link;
                    });
            coordinator.onOffer(offer(CHANNEL_ID, "tcp"));
            assertTrue(link.awaitStarted());

            coordinator.onMediaClose(close(CHANNEL_ID));
            coordinator.onMediaClose(close(CHANNEL_ID));
            coordinator.onMediaClose(null);

            assertTrue(link.awaitClosed());
            assertNull(coordinator.getOpenChannelIdHex());
            assertEquals(1, sink.events.size());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void reset_forgetsEveryChannelAndClosesTheLink() throws Exception {
        RecordingSink sink = new RecordingSink();
        FakeLink link = new FakeLink(true, null);
        link.block();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            MediaChannelCoordinator coordinator = new MediaChannelCoordinator(sink, executor,
                    (offer, status) -> {
                        link.attach(status);
                        return link;
                    });
            coordinator.onOffer(offer(CHANNEL_ID, "tcp"));
            assertTrue(link.awaitStarted());

            coordinator.reset();
            assertTrue(link.awaitClosed());
            assertNull(coordinator.getOpenChannelIdHex());
            assertNull(coordinator.getLastFailureReason());

            coordinator.onOffer(offer(CHANNEL_ID, "tcp"));
            assertTrue(link.awaitStarted());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void refusedOffer_reportsItsReasonToTheLog() {
        RecordingSink sink = new RecordingSink();
        List<String> warnings = new ArrayList<>();
        MediaChannelLog log = new MediaChannelLog() {
            @Override
            public void info(String message) {
            }

            @Override
            public void warn(String message) {
                warnings.add(message);
            }
        };
        MediaChannelCoordinator coordinator = new MediaChannelCoordinator(sink, Runnable::run,
                (offer, status) -> new FakeLink(true, null), log);

        coordinator.onOffer(MediaOffer.getDefaultInstance());
        coordinator.onOffer(offer(CHANNEL_ID, "quic"));

        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("FAILED"));
        assertTrue(warnings.get(0).contains(MediaChannelCoordinator.REASON_UNSUPPORTED));
    }

    @Test
    public void declaredTransports_namesBothChannels() {
        String[] declared = MediaChannelCoordinator.declaredTransports();

        assertEquals(2, declared.length);
        assertTrue(Arrays.asList(declared).contains(MediaTransportKind.TCP.getLabel()));
        assertTrue(Arrays.asList(declared).contains(MediaTransportKind.UDP.getLabel()));
        assertFalse(declared[0].equals(declared[1]));
    }

    private static MediaChannelCoordinator coordinator(RecordingSink sink, FakeLink link) {
        return new MediaChannelCoordinator(sink, Runnable::run, (offer, status) -> {
            link.attach(status);
            return link;
        });
    }

    private static MediaOffer offer(byte[] channelId, String transport) {
        return MediaOffer.newBuilder()
                .setChannelId(com.google.protobuf.ByteString.copyFrom(channelId))
                .setTransport(transport)
                .setHost("127.0.0.1")
                .setPort(9100)
                .setSecure(true)
                .build();
    }

    private static MediaClose close(byte[] channelId) {
        return MediaClose.newBuilder()
                .setChannelId(com.google.protobuf.ByteString.copyFrom(channelId))
                .build();
    }

    private static byte[] bytes(int length, int value) {
        byte[] result = new byte[length];
        Arrays.fill(result, (byte) value);
        return result;
    }

    private static String hexOf(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            out.append(Character.forDigit((value >> 4) & 0x0f, 16));
            out.append(Character.forDigit(value & 0x0f, 16));
        }
        return out.toString();
    }

    /**
     * Records the answers the coordinator sends.
     */
    private static final class RecordingSink implements MediaChannelCoordinator.AnswerSink {

        private final List<MediaChannelEvent> events = new ArrayList<>();

        @Override
        public void send(MediaChannelEvent event) {
            events.add(event);
        }
    }

    /**
     * Link that reports a fixed outcome as soon as it starts.
     */
    private static final class FakeLink implements MediaChannelLink {

        private final boolean ready;

        private final String failure;

        private final CountDownLatch started = new CountDownLatch(1);

        private final CountDownLatch released = new CountDownLatch(1);

        private MediaChannelStatusListener status;

        private volatile boolean block;

        private volatile boolean closed;

        FakeLink(boolean ready, String failure) {
            this.ready = ready;
            this.failure = failure;
        }

        void block() {
            block = true;
        }

        boolean awaitStarted() throws InterruptedException {
            return started.await(2, TimeUnit.SECONDS);
        }

        boolean awaitClosed() throws InterruptedException {
            return released.await(2, TimeUnit.SECONDS);
        }

        void attach(MediaChannelStatusListener status) {
            this.status = status;
        }

        @Override
        public void start() {
            started.countDown();
            if (status != null) {
                if (ready) {
                    status.onChannelReady();
                }
                if (failure != null) {
                    status.onChannelFailed(failure);
                }
            }
            if (block) {
                try {
                    released.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        public void close() {
            closed = true;
            released.countDown();
        }
    }
}
