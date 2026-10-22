package com.obby.android.localscreenshare.core.net;

import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link UdpBindPacket}.
 *
 * <p>The bind packet is the whole handshake of a datagram channel, so the tests cover what it must
 * prove: the channel id is readable without keys, the token opens only with the right key, and a
 * modified byte anywhere fails authentication.</p>
 */
public class UdpBindPacketTest {

    private static final byte[] KEY_C2S = bytes(DatagramCipher.KEY_BYTES, 0x11);

    private static final byte[] KEY_S2C = bytes(DatagramCipher.KEY_BYTES, 0x22);

    private static final byte[] SALT = bytes(DatagramCipher.NONCE_SALT_BYTES, 0x33);

    private static final byte[] CHANNEL_ID = bytes(16, 0x44);

    private static final byte[] TOKEN = bytes(32, 0x55);

    private final DatagramCipher cipher = new DatagramCipher(KEY_C2S, SALT);

    @Test
    public void encode_reportsTheChannelWithoutKeys() {
        byte[] datagram = UdpBindPacket.encode(CHANNEL_ID, TOKEN, cipher, 1L);

        assertArrayEquals(CHANNEL_ID, UdpBindPacket.peekChannelId(datagram));
        assertEquals(1L, UdpBindPacket.peekWireSeq(datagram));
        assertEquals(UdpBindPacket.BIND_BYTES, datagram.length);
    }

    @Test
    public void decode_returnsTheTokenOfTheChannel() throws Exception {
        byte[] datagram = UdpBindPacket.encode(CHANNEL_ID, TOKEN, cipher, 1L);

        UdpBindPacket.BindRequest request = UdpBindPacket.decode(datagram, cipher);

        assertArrayEquals(CHANNEL_ID, request.getChannelId());
        assertArrayEquals(TOKEN, request.getToken());
        assertEquals(32, request.getChannelIdHex().length());
    }

    @Test
    public void anotherKey_doesNotOpenTheBind() {
        byte[] datagram = UdpBindPacket.encode(CHANNEL_ID, TOKEN, cipher, 1L);
        DatagramCipher other = new DatagramCipher(KEY_S2C, SALT);

        assertThrows(DatagramCipher.AuthenticationException.class,
                () -> UdpBindPacket.decode(datagram, other));
    }

    @Test
    public void modifiedDatagram_doesNotOpen() {
        byte[] datagram = UdpBindPacket.encode(CHANNEL_ID, TOKEN, cipher, 1L);
        datagram[datagram.length - 1] ^= 0x01;

        assertThrows(DatagramCipher.AuthenticationException.class,
                () -> UdpBindPacket.decode(datagram, cipher));
    }

    @Test
    public void truncatedOrOversizedDatagram_isRejected() {
        byte[] datagram = UdpBindPacket.encode(CHANNEL_ID, TOKEN, cipher, 1L);

        assertThrows(IOException.class, () -> UdpBindPacket.decode(
                Arrays.copyOf(datagram, datagram.length - 1), cipher));
        assertThrows(IOException.class, () -> UdpBindPacket.decode(
                Arrays.copyOf(datagram, datagram.length + 1), cipher));
    }

    @Test
    public void constructor_rejectsWrongLengths() {
        assertThrows(IllegalArgumentException.class,
                () -> UdpBindPacket.encode(new byte[3], TOKEN, cipher, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> UdpBindPacket.encode(CHANNEL_ID, new byte[3], cipher, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> UdpBindPacket.encode(CHANNEL_ID, TOKEN, null, 1L));
    }

    @Test
    public void peekOfAShortDatagram_isEmpty() {
        assertTrue(UdpBindPacket.peekChannelId(new byte[3]) == null);
        assertEquals(0L, UdpBindPacket.peekWireSeq(new byte[3]));
        assertNotNull(bytes(1, 1));
    }

    private static byte[] bytes(int length, int value) {
        byte[] result = new byte[length];
        Arrays.fill(result, (byte) value);
        return result;
    }
}
