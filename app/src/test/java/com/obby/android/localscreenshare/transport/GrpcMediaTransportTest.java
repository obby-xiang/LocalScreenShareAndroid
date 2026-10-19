package com.obby.android.localscreenshare.transport;

import static org.junit.Assert.assertEquals;
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

import io.grpc.stub.StreamObserver;

/** Unit tests for {@link GrpcMediaTransport}. */
public class GrpcMediaTransportTest {

    private static final StreamKey KEY = new StreamKey("session", "stream");

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

    /** Records every message, tolerating only sequential {@code onNext} calls. */
    private static final class RecordingObserver implements StreamObserver<ServerMessage> {
        final List<ServerMessage> messages = new ArrayList<>();

        @Override
        public void onNext(ServerMessage value) {
            messages.add(value);
        }

        @Override
        public void onError(Throwable t) {
        }

        @Override
        public void onCompleted() {
        }
    }

    /** Records the maximum number of overlapping {@code onNext} invocations. */
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
