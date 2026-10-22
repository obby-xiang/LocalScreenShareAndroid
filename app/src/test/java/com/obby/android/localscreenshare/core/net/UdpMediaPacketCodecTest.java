package com.obby.android.localscreenshare.core.net;

import com.obby.android.localscreenshare.core.codec.h264.H264CodecPolicy;
import com.obby.android.localscreenshare.core.model.AccessUnitFlags;
import com.obby.android.localscreenshare.core.model.CodecConfigPart;
import com.obby.android.localscreenshare.core.model.EncodedAccessUnit;
import com.obby.android.localscreenshare.core.model.JoinInfo;
import com.obby.android.localscreenshare.core.model.StreamKey;
import com.obby.android.localscreenshare.core.support.H264Fixtures;
import com.obby.android.localscreenshare.transport.ScreenStreamProtoMapper;

import org.junit.Test;

import java.io.EOFException;
import java.nio.ByteBuffer;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link UdpMediaPacketCodec}.
 *
 * <p>The datagram format is what a lossy path will mangle, so the tests pin the exact bounds, the
 * per-fragment identity and the refusal to interpret anything that does not fit the layout.</p>
 */
public class UdpMediaPacketCodecTest {

    private static final byte[] KEY = bytes(DatagramCipher.KEY_BYTES, 0x11);

    private static final byte[] SALT = bytes(DatagramCipher.NONCE_SALT_BYTES, 0x22);

    private static final StreamKey STREAM_KEY = new StreamKey("session-1", "stream-1");

    private final DatagramCipher cipher = new DatagramCipher(KEY, SALT);

    @Test
    public void fragmentCount_coversTheBoundaries() {
        assertEquals(1, UdpMediaPacketCodec.fragmentCount(0));
        assertEquals(1, UdpMediaPacketCodec.fragmentCount(1));
        assertEquals(1, UdpMediaPacketCodec.fragmentCount(UdpMediaPacketCodec.MAX_FRAGMENT_BYTES));
        assertEquals(2,
                UdpMediaPacketCodec.fragmentCount(UdpMediaPacketCodec.MAX_FRAGMENT_BYTES + 1));
        assertEquals(UdpMediaPacketCodec.MAX_FRAGMENT_COUNT,
                UdpMediaPacketCodec.fragmentCount(UdpMediaPacketCodec.MAX_AU_BYTES));
        assertThrows(IllegalArgumentException.class,
                () -> UdpMediaPacketCodec.fragmentCount(UdpMediaPacketCodec.MAX_AU_BYTES + 1));
    }

    @Test
    public void singleFragment_roundTripsEveryField() throws Exception {
        MediaFrame frame = MediaFrame.accessUnit(123_456L, MediaFrame.DTS_UNSET, 5, true, true,
                true, null, bytes(64, 0x33));

        UdpMediaPacket packet = UdpMediaPacketCodec.decode(
                UdpMediaPacketCodec.encodeFragment(frame, 9, 0, 1L, cipher), cipher);

        assertEquals(1L, packet.getWireSeq());
        assertEquals(9, packet.getAuId());
        assertEquals(0, packet.getFragmentIndex());
        assertEquals(1, packet.getFragmentCount());
        assertEquals(123_456L, packet.getPtsUs());
        assertEquals(64, packet.getAuSize());
        assertEquals(5, packet.getConfigEpoch());
        assertTrue(packet.isConfig());
        assertTrue(packet.isSyncPoint());
        assertTrue(packet.isJoinPoint());
        assertFalse(packet.isParity());
        assertTrue(packet.isFirstFragment());
        assertTrue(packet.isLastFragment());
        assertArrayEquals(bytes(64, 0x33), packet.getFragment());
    }

    @Test
    public void multiFragment_reassemblesThePayloadAndKeepsTheOffsetsInOrder() throws Exception {
        byte[] payload = payload(3 * UdpMediaPacketCodec.MAX_FRAGMENT_BYTES + 100);
        MediaFrame frame = MediaFrame.accessUnit(7_000L, MediaFrame.DTS_UNSET, 2, false, true,
                true, null, payload);
        int count = UdpMediaPacketCodec.fragmentCount(payload.length);
        assertEquals(4, count);

        byte[] rebuilt = new byte[payload.length];
        for (int i = 0; i < count; i++) {
            UdpMediaPacket packet = UdpMediaPacketCodec.decode(
                    UdpMediaPacketCodec.encodeFragment(frame, 3, i, 10L + i, cipher), cipher);
            assertEquals(i * UdpMediaPacketCodec.MAX_FRAGMENT_BYTES, packet.getFragmentOffset());
            assertTrue(packet.getFragmentLength()
                    <= UdpMediaPacketCodec.MAX_FRAGMENT_BYTES);
            System.arraycopy(packet.getFragment(), 0, rebuilt, packet.getFragmentOffset(),
                    packet.getFragmentLength());
        }

        assertArrayEquals(payload, rebuilt);
    }

    @Test
    public void datagramNeverExceedsTheMtu() {
        byte[] payload = payload(5 * UdpMediaPacketCodec.MAX_FRAGMENT_BYTES);
        MediaFrame frame = MediaFrame.accessUnit(0L, MediaFrame.DTS_UNSET, 1, false, false, false,
                null, payload);

        for (int i = 0; i < 5; i++) {
            byte[] datagram = UdpMediaPacketCodec.encodeFragment(frame, 1, i, 100L + i, cipher);
            assertTrue(datagram.length <= UdpMediaPacketCodec.MAX_DATAGRAM_BYTES);
        }
    }

    @Test
    public void joinPartsAreNotPartOfThisFormatVersion() throws Exception {
        List<CodecConfigPart> parts = Arrays.asList(
                new CodecConfigPart("csd-0", new byte[]{0x0a}),
                new CodecConfigPart("csd-1", new byte[]{0x0b}));
        MediaFrame frame = MediaFrame.accessUnit(0L, MediaFrame.DTS_UNSET, 1, false, true, true,
                parts, payload(2 * UdpMediaPacketCodec.MAX_FRAGMENT_BYTES));

        UdpMediaPacket first = UdpMediaPacketCodec.decode(
                UdpMediaPacketCodec.encodeFragment(frame, 4, 0, 1L, cipher), cipher);

        // The join decision survives through the flags; the parts themselves travel in the
        // descriptor and in the bitstream, which keeps every fragment fixed size.
        assertTrue(first.isJoinPoint());
        assertTrue(first.isSyncPoint());
        assertTrue(first.getJoinParts().isEmpty());
        assertEquals(2 * UdpMediaPacketCodec.MAX_FRAGMENT_BYTES, first.getAuSize());
    }

    @Test
    public void datagramClaimingJoinConfiguration_isRejected() {
        ByteBuffer body = ByteBuffer.allocate(UdpMediaPacketCodec.HEADER_BYTES);
        body.put((byte) 0x42); // version 1 with HAS_JOIN set
        body.put((byte) 0);
        body.putShort((short) 1);
        body.putInt(1);
        body.putLong(0L);
        body.putInt(0);
        body.putShort((short) 1);
        body.putShort((short) 0);
        body.putInt(0);
        byte[] ciphertext = cipher.encrypt(1L, body.array());
        ByteBuffer datagram = ByteBuffer.allocate(8 + ciphertext.length);
        datagram.putLong(1L);
        datagram.put(ciphertext);

        assertThrows(IOException.class,
                () -> UdpMediaPacketCodec.decode(datagram.array(), cipher));
    }

    @Test
    public void modifiedDatagram_doesNotAuthenticate() {
        byte[] datagram = UdpMediaPacketCodec.encodeFragment(frame(4), 1, 0, 1L, cipher);
        datagram[datagram.length - 1] ^= 0x01;

        assertThrows(DatagramCipher.AuthenticationException.class,
                () -> UdpMediaPacketCodec.decode(datagram, cipher));
    }

    @Test
    public void truncatedDatagram_isRejected() {
        byte[] datagram = UdpMediaPacketCodec.encodeFragment(frame(4), 1, 0, 1L, cipher);

        assertThrows(EOFException.class,
                () -> UdpMediaPacketCodec.decode(Arrays.copyOf(datagram, 10), cipher));
        assertThrows(IOException.class,
                () -> UdpMediaPacketCodec.decode(Arrays.copyOf(datagram,
                        datagram.length - 3), cipher));
    }

    @Test
    public void onlyAccessUnitsTravelOnDatagrams() {
        MediaFrame descriptor = MediaFrame.descriptor(0L, 1, new byte[]{0x01});

        assertThrows(IllegalArgumentException.class,
                () -> UdpMediaPacketCodec.encodeFragment(descriptor, 1, 0, 1L, cipher));
    }

    @Test
    public void fragmentIndexOutOfRange_isRejected() {
        MediaFrame frame = frame(4);

        assertThrows(IllegalArgumentException.class,
                () -> UdpMediaPacketCodec.encodeFragment(frame, 1, 1, 1L, cipher));
        assertThrows(IllegalArgumentException.class,
                () -> UdpMediaPacketCodec.encodeFragment(frame, 1, -1, 1L, cipher));
    }

    @Test
    public void joinPointDecision_isTheSameOnBothPaths() throws Exception {
        EncodedAccessUnit unit = new EncodedAccessUnit(STREAM_KEY, 1, 0L, 0L,
                EncodedAccessUnit.DTS_UNSET, AccessUnitFlags.of(true, true, false),
                new JoinInfo(1, Collections.singletonList(
                        new CodecConfigPart("csd-0", H264Fixtures.sps()))), null,
                H264Fixtures.annexB(H264Fixtures.sps(), H264Fixtures.pps(),
                        H264Fixtures.idrSlice()));
        H264CodecPolicy policy = new H264CodecPolicy();
        MediaFrame frame = MediaFrame.accessUnit(unit.getPtsUs(), MediaFrame.DTS_UNSET,
                unit.getConfigEpoch(), unit.getFlags().isConfig(), unit.getFlags().isSyncPoint(),
                unit.getFlags().isJoinPoint(), unit.getJoinInfo().getRequiredConfigParts(),
                unit.getPayload());

        UdpMediaPacket packet = UdpMediaPacketCodec.decode(
                UdpMediaPacketCodec.encodeFragment(frame, 1, 0, 1L, cipher), cipher);
        MediaFrame rebuilt = MediaFrame.accessUnit(packet.getPtsUs(), MediaFrame.DTS_UNSET,
                packet.getConfigEpoch(), packet.isConfig(), packet.isSyncPoint(),
                packet.isJoinPoint(), packet.getJoinParts(), packet.getFragment());
        EncodedAccessUnit throughDatagram = new EncodedAccessUnit(STREAM_KEY,
                rebuilt.getConfigEpoch(), unit.getSequence(), rebuilt.getPtsUs(),
                rebuilt.getPtsUs(), AccessUnitFlags.of(rebuilt.isSyncPoint(),
                        rebuilt.isJoinPoint(), rebuilt.isConfig()),
                new JoinInfo(rebuilt.getConfigEpoch(), rebuilt.getJoinParts()), null,
                rebuilt.getPayload());
        EncodedAccessUnit throughStream = ScreenStreamProtoMapper.toAccessUnit(STREAM_KEY,
                ScreenStreamProtoMapper.toProtoAccessUnit(unit));

        assertTrue(policy.isJoinPoint(unit));
        assertEquals(policy.isJoinPoint(unit), policy.isJoinPoint(throughDatagram));
        assertEquals(policy.isJoinPoint(unit), policy.isJoinPoint(throughStream));
    }

    private MediaFrame frame(int size) {
        return MediaFrame.accessUnit(0L, MediaFrame.DTS_UNSET, 1, false, false, false, null,
                payload(size));
    }

    private static byte[] payload(int size) {
        byte[] payload = new byte[size];
        for (int i = 0; i < size; i++) {
            payload[i] = (byte) (i * 31 + 7);
        }
        return payload;
    }

    private static byte[] bytes(int length, int value) {
        byte[] result = new byte[length];
        Arrays.fill(result, (byte) value);
        return result;
    }
}
