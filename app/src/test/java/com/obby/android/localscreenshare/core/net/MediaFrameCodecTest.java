package com.obby.android.localscreenshare.core.net;

import com.obby.android.localscreenshare.core.model.CodecConfigPart;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
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
 * Unit tests for {@link MediaFrameCodec}.
 *
 * <p>The frame format is the wire contract of the dedicated channel, so every field is asserted to
 * survive a round trip and every malformed shape is asserted to fail loudly: a receiver that
 * guessed instead of failing would decode garbage into the render pipeline.</p>
 */
public class MediaFrameCodecTest {

    private static final byte[] PAYLOAD = {0x01, 0x02, 0x03, 0x04};

    @Test
    public void descriptor_roundTrips() throws Exception {
        MediaFrame frame = MediaFrame.descriptor(1_234_567L, 7, PAYLOAD);

        MediaFrame decoded = MediaFrameCodec.decode(MediaFrameCodec.encode(frame));

        assertEquals(MediaFrame.Type.DESCRIPTOR, decoded.getType());
        assertEquals(1_234_567L, decoded.getPtsUs());
        assertEquals(7, decoded.getConfigEpoch());
        assertArrayEquals(PAYLOAD, decoded.getPayload());
        assertFalse(decoded.hasDtsUs());
        assertFalse(decoded.isJoinPoint());
    }

    @Test
    public void accessUnit_roundTripsWithFlagsAndJoinParts() throws Exception {
        List<CodecConfigPart> joinParts = Arrays.asList(
                new CodecConfigPart("csd-0", new byte[]{0x0a, 0x0b}),
                new CodecConfigPart("csd-1", new byte[]{0x0c}));
        MediaFrame frame = MediaFrame.accessUnit(9_000L, MediaFrame.DTS_UNSET, 3, true, true, true,
                joinParts, PAYLOAD);

        MediaFrame decoded = MediaFrameCodec.decode(MediaFrameCodec.encode(frame));

        assertEquals(MediaFrame.Type.ACCESS_UNIT, decoded.getType());
        assertEquals(9_000L, decoded.getPtsUs());
        assertEquals(3, decoded.getConfigEpoch());
        assertTrue(decoded.isConfig());
        assertTrue(decoded.isSyncPoint());
        assertTrue(decoded.isJoinPoint());
        assertFalse(decoded.hasDtsUs());
        assertEquals(2, decoded.getJoinParts().size());
        assertEquals("csd-0", decoded.getJoinParts().get(0).getId());
        assertArrayEquals(new byte[]{0x0a, 0x0b}, decoded.getJoinParts().get(0).getPayload());
        assertArrayEquals(PAYLOAD, decoded.getPayload());
    }

    @Test
    public void accessUnit_roundTripsWithADecodeTimestamp() throws Exception {
        MediaFrame frame = MediaFrame.accessUnit(500L, 400L, 0, false, false, false,
                Collections.emptyList(), PAYLOAD);

        MediaFrame decoded = MediaFrameCodec.decode(MediaFrameCodec.encode(frame));

        assertTrue(decoded.hasDtsUs());
        assertEquals(400L, decoded.getDtsUs());
        assertEquals(500L, decoded.getPtsUs());
    }

    @Test
    public void frameWithoutAPayload_stillRoundTrips() throws Exception {
        MediaFrame frame = MediaFrame.descriptor(0L, 1, null);

        MediaFrame decoded = MediaFrameCodec.decode(MediaFrameCodec.encode(frame));

        assertEquals(0, decoded.getPayloadLength());
    }

    @Test
    public void oversizedPayload_isRejectedWhenEncoding() {
        byte[] tooLarge = new byte[MediaFrameCodec.MAX_PAYLOAD_BYTES + 1];
        MediaFrame frame = MediaFrame.descriptor(0L, 1, tooLarge);

        assertThrows(IllegalArgumentException.class, () -> MediaFrameCodec.encode(frame));
    }

    @Test
    public void unknownVersion_isRejected() {
        byte[] bytes = MediaFrameCodec.encode(MediaFrame.descriptor(0L, 1, PAYLOAD));
        bytes[15] = 2;

        assertThrows(IOException.class, () -> MediaFrameCodec.decode(bytes));
    }

    @Test
    public void unknownType_isRejected() {
        byte[] bytes = MediaFrameCodec.encode(MediaFrame.descriptor(0L, 1, PAYLOAD));
        bytes[14] = 9;

        assertThrows(IOException.class, () -> MediaFrameCodec.decode(bytes));
    }

    @Test
    public void truncatedBody_isRejected() {
        byte[] bytes = MediaFrameCodec.encode(MediaFrame.descriptor(0L, 1, PAYLOAD));

        assertThrows(IOException.class,
                () -> MediaFrameCodec.decode(Arrays.copyOf(bytes, bytes.length - 1)));
        assertThrows(EOFException.class, () -> MediaFrameCodec.decode(new byte[4]));
    }

    @Test
    public void extraBodyBytes_areRejected() {
        byte[] bytes = MediaFrameCodec.encode(MediaFrame.descriptor(0L, 1, PAYLOAD));
        byte[] longer = Arrays.copyOf(bytes, bytes.length + 1);

        assertThrows(IOException.class, () -> MediaFrameCodec.decode(longer));
    }

    @Test
    public void streamRoundTrip_readsFramesInOrder() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MediaFrameCodec.write(out, MediaFrame.descriptor(0L, 1, PAYLOAD));
        MediaFrameCodec.write(out, MediaFrame.accessUnit(1_000L, MediaFrame.DTS_UNSET, 1, false,
                true, true, Collections.emptyList(), PAYLOAD));
        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());

        MediaFrame first = MediaFrameCodec.read(in);
        MediaFrame second = MediaFrameCodec.read(in);

        assertEquals(MediaFrame.Type.DESCRIPTOR, first.getType());
        assertEquals(MediaFrame.Type.ACCESS_UNIT, second.getType());
        assertEquals(1, second.getConfigEpoch());
        assertThrows(EOFException.class, () -> MediaFrameCodec.read(in));
    }
}
