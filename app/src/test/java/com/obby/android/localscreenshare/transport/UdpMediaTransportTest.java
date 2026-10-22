package com.obby.android.localscreenshare.transport;

import com.obby.android.localscreenshare.core.metrics.PipelineMetrics;
import com.obby.android.localscreenshare.core.model.AccessUnitFlags;
import com.obby.android.localscreenshare.core.model.BitstreamFormat;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.JoinInfo;
import com.obby.android.localscreenshare.core.model.StreamDescriptor;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.net.DatagramCipher;
import com.obby.android.localscreenshare.core.net.MediaTransportKind;
import com.obby.android.localscreenshare.core.net.UdpMediaPacketCodec;
import com.obby.android.localscreenshare.core.support.MutableNanoClock;

import org.junit.After;
import org.junit.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link UdpMediaTransport}.
 *
 * <p>The transport turns one access unit into a run of datagrams; the tests read them back on a
 * loopback socket, so fragmentation, sequence numbers and unit ids are checked as they leave.</p>
 */
public class UdpMediaTransportTest {

    private static final StreamKey STREAM_KEY = new StreamKey("session-1", "stream-1");

    private static final byte[] KEY_S2C = new byte[DatagramCipher.KEY_BYTES];

    private static final byte[] SALT = new byte[DatagramCipher.NONCE_SALT_BYTES];

    private final MutableNanoClock clock = new MutableNanoClock();

    private DatagramSocket peer;

    private DatagramSocket socket;

    @After
    public void tearDown() {
        if (socket != null) {
            socket.close();
        }
        if (peer != null) {
            peer.close();
        }
    }

    @Test
    public void sendDescriptor_isANoOp() throws Exception {
        UdpMediaTransport transport = transport();

        transport.sendDescriptor(descriptor());

        assertEquals(MediaTransportKind.UDP, transport.getMediaTransportKind());
        assertEquals(0L, transport.getLastWireSeq());
    }

    @Test
    public void sendAccessUnit_fragmentsTheUnitIntoDatagrams() throws Exception {
        UdpMediaTransport transport = transport();
        int size = 2 * UdpMediaPacketCodec.MAX_FRAGMENT_BYTES + 10;

        transport.sendAccessUnit(accessUnit(size, 0L));

        assertEquals(3L, transport.getLastWireSeq());
        assertEquals(1, transport.getNextAuId());
        DatagramCipher cipher = new DatagramCipher(KEY_S2C, SALT);
        byte[] buffer = new byte[UdpMediaPacketCodec.MAX_DATAGRAM_BYTES];
        for (int i = 0; i < 3; i++) {
            DatagramPacket received = new DatagramPacket(buffer, buffer.length);
            peer.setSoTimeout(1_000);
            peer.receive(received);
            byte[] bytes = Arrays.copyOf(received.getData(), received.getLength());
            com.obby.android.localscreenshare.core.net.UdpMediaPacket packet =
                    UdpMediaPacketCodec.decode(bytes, cipher);
            assertEquals(i, packet.getFragmentIndex());
            assertEquals(3, packet.getFragmentCount());
            assertEquals(0, packet.getAuId());
            assertEquals(size, packet.getAuSize());
            assertEquals(i + 1, packet.getWireSeq());
        }
    }

    @Test
    public void unitIdsAndSequencesKeepAdvancing() throws Exception {
        UdpMediaTransport transport = transport();

        transport.sendAccessUnit(accessUnit(10, 33_000L));
        transport.sendAccessUnit(accessUnit(10, 66_000L));

        assertEquals(2L, transport.getLastWireSeq());
        assertEquals(2, transport.getNextAuId());
    }

    @Test
    public void sendAfterClose_failsLikeAnyBrokenTransport() {
        UdpMediaTransport transport = transport();
        transport.close();

        assertThrows(java.io.IOException.class,
                () -> transport.sendAccessUnit(accessUnit(10, 0L)));
        assertTrue(transport.isClosed());
    }

    private UdpMediaTransport transport() {
        try {
            peer = new DatagramSocket(0);
            socket = new DatagramSocket();
            return new UdpMediaTransport(socket,
                    new InetSocketAddress("127.0.0.1", peer.getLocalPort()),
                    new DatagramCipher(KEY_S2C, SALT), PipelineMetrics.NONE);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static EncodedAccessUnit accessUnit(int size, long ptsUs) {
        byte[] payload = new byte[size];
        Arrays.fill(payload, (byte) 0x21);
        return new EncodedAccessUnit(STREAM_KEY, 1, 0L, ptsUs, EncodedAccessUnit.DTS_UNSET,
                AccessUnitFlags.of(true, true, false),
                new JoinInfo(1, Collections.emptyList()), null, payload);
    }

    private static StreamDescriptor descriptor() {
        return StreamDescriptor.builder(STREAM_KEY, 1, "h264", "video/avc",
                        BitstreamFormat.ANNEX_B)
                .codedSize(64, 48)
                .visibleSize(64, 48)
                .configParts(Collections.emptyList())
                .build();
    }
}
