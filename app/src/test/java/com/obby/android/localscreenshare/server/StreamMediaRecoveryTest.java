package com.obby.android.localscreenshare.server;

import com.obby.android.localscreenshare.core.stream.MediaTransport;
import com.obby.android.localscreenshare.core.support.FakeTransport;

import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

/**
 * Unit tests for {@link StreamMediaRecovery}.
 *
 * <p>The fallback has exactly two outcomes and both matter for the call: a media channel failure
 * returns the carrier and asks for a fresh join point, while a carrier failure is unrecoverable
 * because the carrier also carries the control stream.</p>
 */
public class StreamMediaRecoveryTest {

    @Test
    public void mediaChannelFailure_returnsTheCarrierAndRequestsAJoinPoint() {
        FakeTransport carrier = new FakeTransport();
        FakeTransport channel = new FakeTransport();
        AtomicInteger requests = new AtomicInteger();
        StreamMediaRecovery recovery = new StreamMediaRecovery(carrier, requests::incrementAndGet);

        MediaTransport replacement = recovery.onTransportFailed(channel,
                new IOException("channel reset"));

        assertSame(carrier, replacement);
        assertEquals(1, requests.get());
    }

    @Test
    public void carrierFailure_isUnrecoverableAndRequestsNothing() {
        FakeTransport carrier = new FakeTransport();
        AtomicInteger requests = new AtomicInteger();
        StreamMediaRecovery recovery = new StreamMediaRecovery(carrier, requests::incrementAndGet);

        assertNull(recovery.onTransportFailed(carrier, new IOException("carrier reset")));
        assertEquals(0, requests.get());
    }

    @Test
    public void constructor_rejectsMissingArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> new StreamMediaRecovery(null, () -> {
                }));
        assertThrows(IllegalArgumentException.class,
                () -> new StreamMediaRecovery(new FakeTransport(), null));
    }
}
