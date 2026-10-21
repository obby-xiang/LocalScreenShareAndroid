package com.obby.android.localscreenshare.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.obby.android.localscreenshare.core.model.AccessUnitFlags;
import com.obby.android.localscreenshare.core.model.BitstreamFormat;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.grpc.screenstream.ServerMessage;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;

/**
 * Unit tests for {@link GrpcMediaTransport}.
 */
public class GrpcMediaTransportTest {

    private static final StreamKey KEY = new StreamKey("session", "stream");

    @Test
    public void sendPongEmitsTheEchoAndIsSilentAfterClose() throws IOException {
        RecordingObserver observer = new RecordingObserver();
        GrpcMediaTransport transport = new GrpcMediaTransport(observer, false);

        transport.sendPong(9, 4_242L);

        assertEquals(1, observer.messages.size());
        ServerMessage message = observer.messages.get(0);
        assertEquals(ServerMessage.PayloadCase.PONG, message.getPayloadCase());
        assertEquals(9, message.getPong().getSeq());
        assertEquals(4_242L, message.getPong().getClientUptimeMs());

        transport.close();
        transport.sendPong(10, 5_000L);

        assertEquals("a closing transport drops the echo instead of throwing", 1,
                observer.messages.size());
    }

    @Test
    public void sendDescriptorEmitsDescriptorPayloadWithSecureFlag() throws IOException {
        RecordingObserver observer = new RecordingObserver();
        GrpcMediaTransport transport = new GrpcMediaTransport(observer, true);
        StreamDescriptor descriptor = StreamDescriptor.builder(KEY, 1, "jpeg", "image/jpeg",
                        BitstreamFormat.JPEG_FRAME)
                .codedSize(640, 480)
                .build();

        transport.sendDescriptor(descriptor);

        assertEquals(1, observer.messages.size());
        ServerMessage message = observer.messages.get(0);
        assertEquals(ServerMessage.PayloadCase.DESCRIPTOR, message.getPayloadCase());
        assertTrue(message.getDescriptor().getSecure());
        assertEquals(640, message.getDescriptor().getCodedWidth());
    }

    @Test
    public void sendAccessUnitEmitsAccessUnitPayload() throws IOException {
        RecordingObserver observer = new RecordingObserver();
        GrpcMediaTransport transport = new GrpcMediaTransport(observer, false);
        EncodedAccessUnit accessUnit = new EncodedAccessUnit(KEY, 1, 0L, 100L,
                EncodedAccessUnit.DTS_UNSET, AccessUnitFlags.none(), null, null,
                new byte[]{0x01});

        transport.sendAccessUnit(accessUnit);

        assertEquals(1, observer.messages.size());
        ServerMessage message = observer.messages.get(0);
        assertEquals(ServerMessage.PayloadCase.ACCESS_UNIT, message.getPayloadCase());
        assertEquals(1, message.getAccessUnit().getConfigEpoch());
    }

    @Test
    public void closeIsIdempotent() {
        RecordingObserver observer = new RecordingObserver();
        GrpcMediaTransport transport = new GrpcMediaTransport(observer, false);
        transport.close();
        transport.close();

        // close() only releases the outbound side; the owner of the call ends the RPC.
        assertNull(observer.error);
        assertFalse(observer.completed);
    }

    @Test
    public void abortTerminatesTheRpcWithAbortedStatus() {
        RecordingObserver observer = new RecordingObserver();
        GrpcMediaTransport transport = new GrpcMediaTransport(observer, false);

        transport.abort("session taken over by a newer connection");

        assertNotNull(observer.error);
        assertEquals(1, observer.errorCount);
        assertEquals(Status.Code.ABORTED, Status.fromThrowable(observer.error).getCode());
        assertEquals("session taken over by a newer connection",
                Status.fromThrowable(observer.error).getDescription());
        assertFalse(observer.completed);
    }

    @Test
    public void abortIsIdempotentAndWinsOverClose() {
        RecordingObserver observer = new RecordingObserver();
        GrpcMediaTransport transport = new GrpcMediaTransport(observer, false);

        transport.abort("session taken over");
        transport.abort("session taken over");
        transport.close();

        assertEquals(1, observer.errorCount);
        assertFalse(observer.completed);
        assertThrows(IOException.class, () -> transport.sendDescriptor(
                StreamDescriptor.builder(KEY, 1, "jpeg", "image/jpeg", BitstreamFormat.JPEG_FRAME)
                        .codedSize(1, 1).build()));
    }

    @Test
    public void abortAfterCloseStillTerminatesTheRpc() {
        RecordingObserver observer = new RecordingObserver();
        GrpcMediaTransport transport = new GrpcMediaTransport(observer, false);

        transport.close();
        transport.abort("session taken over");
        transport.abort("session taken over");

        // A revoked session must not leave the peer waiting, even if the plain release ran first.
        assertNotNull(observer.error);
        assertEquals(1, observer.errorCount);
        assertEquals(Status.Code.ABORTED, Status.fromThrowable(observer.error).getCode());
        assertFalse(observer.completed);
    }

    @Test
    public void sendAfterCloseThrows() {
        RecordingObserver observer = new RecordingObserver();
        GrpcMediaTransport transport = new GrpcMediaTransport(observer, false);
        transport.close();
        assertThrows(IOException.class, () -> transport.sendDescriptor(
                StreamDescriptor.builder(KEY, 1, "jpeg", "image/jpeg", BitstreamFormat.JPEG_FRAME)
                        .codedSize(1, 1).build()));
    }

    @Test
    public void concurrentCloseAndAbortTerminateOnlyOnce() throws Exception {
        RecordingObserver observer = new RecordingObserver();
        GrpcMediaTransport transport = new GrpcMediaTransport(observer, false);

        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final boolean abort = i % 2 == 0;
            futures.add(executor.submit(() -> {
                start.await();
                if (abort) {
                    transport.abort("session taken over");
                } else {
                    transport.close();
                }
                return null;
            }));
        }
        start.countDown();
        for (java.util.concurrent.Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        // The first abort terminates the RPC; further aborts and plain closes add nothing.
        assertEquals(1, observer.errorCount);
        assertFalse(observer.completed);
    }

    @Test
    public void concurrentSendsAreSerialized() throws Exception {
        TrackingConcurrencyObserver observer = new TrackingConcurrencyObserver();
        GrpcMediaTransport transport = new GrpcMediaTransport(observer, false);
        StreamDescriptor descriptor = StreamDescriptor.builder(KEY, 1, "jpeg", "image/jpeg",
                        BitstreamFormat.JPEG_FRAME).codedSize(640, 480).build();

        int threads = 8;
        int perThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int j = 0; j < perThread; j++) {
                    try {
                        transport.sendDescriptor(descriptor);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }));
        }
        start.countDown();
        for (java.util.concurrent.Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertEquals(threads * perThread, observer.count.get());
        assertEquals(1, observer.maxConcurrency.get());
    }

    /**
     * Records every message, tolerating only sequential {@code onNext} calls.
     */
    private static final class RecordingObserver implements StreamObserver<ServerMessage> {
        final List<ServerMessage> messages = new ArrayList<>();
        Throwable error;
        int errorCount;
        boolean completed;

        @Override
        public void onNext(ServerMessage value) {
            messages.add(value);
        }

        @Override
        public void onError(Throwable t) {
            error = t;
            errorCount++;
        }

        @Override
        public void onCompleted() {
            completed = true;
        }
    }

    /**
     * Records the maximum number of overlapping {@code onNext} invocations.
     */
    private static final class TrackingConcurrencyObserver implements StreamObserver<ServerMessage> {
        final AtomicInteger count = new AtomicInteger();
        final AtomicInteger current = new AtomicInteger();
        final AtomicInteger maxConcurrency = new AtomicInteger();

        @Override
        public void onNext(ServerMessage value) {
            int depth = current.incrementAndGet();
            maxConcurrency.accumulateAndGet(depth, Math::max);
            try {
                count.incrementAndGet();
            } finally {
                current.decrementAndGet();
            }
        }

        @Override
        public void onError(Throwable t) {
        }

        @Override
        public void onCompleted() {
        }
    }
}
