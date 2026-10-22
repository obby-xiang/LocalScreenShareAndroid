package com.obby.android.localscreenshare.core.net;

import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

/**
 * Unit tests for {@link MediaChannelHandshake}.
 *
 * <p>The greeting is fixed size on purpose, so a wrong magic, version or length must fail instead of
 * being resynchronized on: the channel is either bound to a call or it is not.</p>
 */
public class MediaChannelHandshakeTest {

    private static final byte[] CHANNEL_ID = bytes(16, 0x11);

    private static final byte[] TOKEN = bytes(32, 0x22);

    @Test
    public void hello_roundTrips() throws Exception {
        byte[] encoded = MediaChannelHandshake.encodeHello(MediaTransportKind.TCP, CHANNEL_ID,
                TOKEN);

        assertEquals(MediaChannelHandshake.HELLO_BYTES, encoded.length);
        MediaChannelHandshake.Hello hello = MediaChannelHandshake.decodeHello(encoded);
        assertEquals(MediaTransportKind.TCP, hello.getTransport());
        assertArrayEquals(CHANNEL_ID, hello.getChannelId());
        assertArrayEquals(TOKEN, hello.getToken());
        assertNotNull(hello.getChannelIdHex());
        assertEquals(32, hello.getChannelIdHex().length());
    }

    @Test
    public void hello_rejectsAWrongLength() {
        byte[] shortHello = new byte[MediaChannelHandshake.HELLO_BYTES - 1];

        assertThrows(IOException.class,
                () -> MediaChannelHandshake.decodeHello(shortHello));
        assertThrows(IOException.class, () -> MediaChannelHandshake.decodeHello(null));
    }

    @Test
    public void hello_rejectsAWrongMagicVersionAndTransport() {
        byte[] encoded = MediaChannelHandshake.encodeHello(MediaTransportKind.TCP, CHANNEL_ID,
                TOKEN);

        byte[] badMagic = encoded.clone();
        badMagic[0] = 'X';
        assertThrows(IOException.class, () -> MediaChannelHandshake.decodeHello(badMagic));

        byte[] badVersion = encoded.clone();
        badVersion[4] = 9;
        assertThrows(IOException.class, () -> MediaChannelHandshake.decodeHello(badVersion));

        byte[] badTransport = encoded.clone();
        badTransport[5] = 7;
        assertThrows(IOException.class, () -> MediaChannelHandshake.decodeHello(badTransport));
    }

    @Test
    public void hello_rejectsTheControlPlaneTransport() {
        assertThrows(IllegalArgumentException.class,
                () -> MediaChannelHandshake.encodeHello(MediaTransportKind.GRPC, CHANNEL_ID,
                        TOKEN));
        assertThrows(IllegalArgumentException.class,
                () -> MediaChannelHandshake.encodeHello(MediaTransportKind.TCP, new byte[3],
                        TOKEN));
        assertThrows(IllegalArgumentException.class,
                () -> MediaChannelHandshake.encodeHello(MediaTransportKind.TCP, CHANNEL_ID,
                        new byte[3]));
    }

    @Test
    public void ack_roundTripsEveryStatus() throws Exception {
        for (MediaChannelHandshake.Status status : MediaChannelHandshake.Status.values()) {
            byte[] encoded = MediaChannelHandshake.encodeAck(status);

            assertEquals(MediaChannelHandshake.ACK_BYTES, encoded.length);
            assertEquals(status, MediaChannelHandshake.decodeAck(encoded));
        }
    }

    @Test
    public void ack_rejectsAWrongMagicLengthOrStatus() {
        byte[] encoded = MediaChannelHandshake.encodeAck(MediaChannelHandshake.Status.OK);
        byte[] badMagic = encoded.clone();
        badMagic[1] = 'X';

        assertThrows(IOException.class, () -> MediaChannelHandshake.decodeAck(badMagic));
        assertThrows(IOException.class,
                () -> MediaChannelHandshake.decodeAck(new byte[MediaChannelHandshake.ACK_BYTES - 1]));
    }

    @Test
    public void ack_unknownStatusCodeIsNull() throws Exception {
        byte[] encoded = MediaChannelHandshake.encodeAck(MediaChannelHandshake.Status.OK);
        encoded[5] = 42;

        assertEquals(null, MediaChannelHandshake.decodeAck(encoded));
    }

    private static byte[] bytes(int length, int value) {
        byte[] result = new byte[length];
        Arrays.fill(result, (byte) value);
        return result;
    }
}
