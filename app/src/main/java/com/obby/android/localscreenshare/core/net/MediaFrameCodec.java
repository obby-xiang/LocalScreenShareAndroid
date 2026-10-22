package com.obby.android.localscreenshare.core.net;

import com.obby.android.localscreenshare.core.model.CodecConfigPart;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Binary framing of the dedicated stream media channel.
 *
 * <p><b>Header.</b> Every frame starts with sixteen bytes, big endian: the presentation timestamp,
 * a word that carries the flags and the payload length, the config epoch, the frame type and the
 * frame version. The first twelve bytes follow the minimal prefix scrcpy uses for the same purpose,
 * and the last four add what this project needs and scrcpy does not: the epoch, so a receiver can
 * tell two decode configurations apart, and the frame type, so a descriptor and an access unit
 * share one channel.</p>
 *
 * <pre>
 * offset size field
 * 0      8    ptsUs
 * 8      4    bit31 CONFIG | bit30 SYNC | bit29 JOIN | bit28 HAS_JOIN_PARTS
 *             bit27 HAS_DTS | bit26..0 payload length
 * 12     2    configEpoch
 * 14     1    type (1 descriptor, 2 access unit)
 * 15     1    version (1)
 * </pre>
 *
 * <p><b>Body.</b> An optional decode timestamp, then the optional join configuration parts, then
 * the payload. A join part is a two-byte id length, the id in UTF-8, a four-byte payload length and
 * the payload; the list starts with its two-byte count. Nothing in the hot path touches a protocol
 * buffer: the descriptor payload is opaque here and is parsed by the transport that owns the
 * channel.</p>
 *
 * <p><b>Failure.</b> A header whose version is unknown, whose type is unknown, whose length exceeds
 * {@link #MAX_PAYLOAD_BYTES} or whose body is truncated is a channel failure, not a frame to skip:
 * the stream has lost sync and a receiver must close the channel instead of guessing. Every
 * malformed case throws an {@link IOException}, so one catch in the reader covers all of them.</p>
 */
public final class MediaFrameCodec {

    /**
     * Length of the frame header, in bytes.
     */
    public static final int HEADER_BYTES = 16;

    /**
     * Version of the frame format this codec writes and reads.
     */
    public static final int VERSION = 1;

    /**
     * Upper bound of one payload, in bytes; eight mebibytes fits the largest access unit the
     * encoder queue admits and bounds the allocation a peer can ask for.
     */
    public static final int MAX_PAYLOAD_BYTES = 8 * 1024 * 1024;

    /**
     * Largest config epoch a frame can carry.
     */
    public static final int MAX_CONFIG_EPOCH = 0xffff;

    /**
     * Largest number of join parts a frame can carry.
     */
    public static final int MAX_JOIN_PARTS = 0xffff;

    private static final int FLAG_CONFIG = 1 << 31;

    private static final int FLAG_SYNC = 1 << 30;

    private static final int FLAG_JOIN = 1 << 29;

    private static final int FLAG_HAS_JOIN_PARTS = 1 << 28;

    private static final int FLAG_HAS_DTS = 1 << 27;

    private static final int SIZE_MASK = (1 << 27) - 1;

    private MediaFrameCodec() {
    }

    /**
     * Encodes one frame into a new array.
     *
     * @param frame frame to encode; must not be null
     * @return the complete frame, header and body
     * @throws IllegalArgumentException if the frame exceeds a format bound
     */
    public static byte[] encode(MediaFrame frame) {
        if (frame == null) {
            throw new IllegalArgumentException("frame must not be null");
        }
        if (frame.getPayloadLength() > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("payload exceeds " + MAX_PAYLOAD_BYTES + " bytes");
        }
        if (frame.getConfigEpoch() < 0 || frame.getConfigEpoch() > MAX_CONFIG_EPOCH) {
            throw new IllegalArgumentException("config epoch out of range");
        }
        List<CodecConfigPart> joinParts = frame.getJoinParts();
        if (joinParts.size() > MAX_JOIN_PARTS) {
            throw new IllegalArgumentException("too many join parts");
        }
        byte[] payload = frame.getPayload();
        int flags = frame.getPayloadLength();
        if (frame.isConfig()) {
            flags |= FLAG_CONFIG;
        }
        if (frame.isSyncPoint()) {
            flags |= FLAG_SYNC;
        }
        if (frame.isJoinPoint()) {
            flags |= FLAG_JOIN;
        }
        if (!joinParts.isEmpty()) {
            flags |= FLAG_HAS_JOIN_PARTS;
        }
        if (frame.hasDtsUs()) {
            flags |= FLAG_HAS_DTS;
        }
        ByteBuffer out = ByteBuffer.allocate(
                HEADER_BYTES + (frame.hasDtsUs() ? 8 : 0) + joinPartsBytes(joinParts)
                        + payload.length);
        out.putLong(frame.getPtsUs());
        out.putInt(flags);
        out.putShort((short) frame.getConfigEpoch());
        out.put((byte) frame.getType().getCode());
        out.put((byte) VERSION);
        putBody(out, frame, payload, joinParts);
        return out.array();
    }

    /**
     * Writes one frame to {@code out} without closing it.
     *
     * @param out   destination; required
     * @param frame frame to write; required
     * @throws IOException if the write fails
     */
    public static void write(OutputStream out, MediaFrame frame) throws IOException {
        if (out == null) {
            throw new IllegalArgumentException("out must not be null");
        }
        out.write(encode(frame));
        out.flush();
    }

    /**
     * Decodes one frame from a complete array.
     *
     * @param bytes frame bytes; must not be null
     * @return the decoded frame
     * @throws IOException if the frame is malformed or truncated
     */
    public static MediaFrame decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < HEADER_BYTES) {
            throw new EOFException("frame shorter than its header");
        }
        ByteBuffer in = ByteBuffer.wrap(bytes);
        long ptsUs = in.getLong();
        int flags = in.getInt();
        int configEpoch = in.getShort() & 0xffff;
        int typeCode = in.get() & 0xff;
        int version = in.get() & 0xff;
        MediaFrame.Type type = requireType(typeCode, version);
        int size = flags & SIZE_MASK;
        if (size > MAX_PAYLOAD_BYTES) {
            throw new IOException("frame payload exceeds " + MAX_PAYLOAD_BYTES + " bytes");
        }
        long dtsUs = MediaFrame.DTS_UNSET;
        if ((flags & FLAG_HAS_DTS) != 0) {
            require(in, 8);
            dtsUs = in.getLong();
        }
        List<CodecConfigPart> joinParts = new ArrayList<>(0);
        if ((flags & FLAG_HAS_JOIN_PARTS) != 0) {
            joinParts = readJoinParts(in);
        }
        if (in.remaining() != size) {
            throw new IOException("frame body has " + in.remaining() + " bytes, header says "
                    + size);
        }
        byte[] payload = new byte[size];
        in.get(payload);
        return build(type, ptsUs, dtsUs, configEpoch, flags, joinParts, payload);
    }

    /**
     * Reads one frame from {@code in}, blocking until it is complete.
     *
     * @param in source; required
     * @return the decoded frame
     * @throws EOFException when the stream ends at a frame boundary or inside a frame
     * @throws IOException    if the frame is malformed
     */
    public static MediaFrame read(InputStream in) throws IOException {
        if (in == null) {
            throw new IllegalArgumentException("in must not be null");
        }
        byte[] header = new byte[HEADER_BYTES];
        readExact(in, header);
        ByteBuffer buffer = ByteBuffer.wrap(header);
        long ptsUs = buffer.getLong();
        int flags = buffer.getInt();
        int configEpoch = buffer.getShort() & 0xffff;
        int typeCode = buffer.get() & 0xff;
        int version = buffer.get() & 0xff;
        MediaFrame.Type type = requireType(typeCode, version);
        int size = flags & SIZE_MASK;
        if (size > MAX_PAYLOAD_BYTES) {
            throw new IOException("frame payload exceeds " + MAX_PAYLOAD_BYTES + " bytes");
        }
        long dtsUs = MediaFrame.DTS_UNSET;
        if ((flags & FLAG_HAS_DTS) != 0) {
            byte[] dts = new byte[8];
            readExact(in, dts);
            dtsUs = ByteBuffer.wrap(dts).getLong();
        }
        List<CodecConfigPart> joinParts = new ArrayList<>(0);
        if ((flags & FLAG_HAS_JOIN_PARTS) != 0) {
            byte[] count = new byte[2];
            readExact(in, count);
            int parts = ByteBuffer.wrap(count).getShort() & 0xffff;
            joinParts = new ArrayList<>(parts);
            for (int i = 0; i < parts; i++) {
                joinParts.add(readJoinPart(in));
            }
        }
        byte[] payload = new byte[size];
        readExact(in, payload);
        return build(type, ptsUs, dtsUs, configEpoch, flags, joinParts, payload);
    }

    private static void putBody(ByteBuffer out, MediaFrame frame, byte[] payload,
                                List<CodecConfigPart> joinParts) {
        if (frame.hasDtsUs()) {
            out.putLong(frame.getDtsUs());
        }
        if (!joinParts.isEmpty()) {
            out.putShort((short) joinParts.size());
            for (CodecConfigPart part : joinParts) {
                byte[] id = part.getId() == null
                        ? new byte[0] : part.getId().getBytes(StandardCharsets.UTF_8);
                byte[] partPayload = part.getPayload() == null ? new byte[0] : part.getPayload();
                out.putShort((short) id.length);
                out.put(id);
                out.putInt(partPayload.length);
                out.put(partPayload);
            }
        }
        out.put(payload);
    }

    private static MediaFrame build(MediaFrame.Type type, long ptsUs, long dtsUs,
                                    int configEpoch, int flags, List<CodecConfigPart> joinParts,
                                    byte[] payload) {
        boolean config = (flags & FLAG_CONFIG) != 0;
        boolean syncPoint = (flags & FLAG_SYNC) != 0;
        boolean joinPoint = (flags & FLAG_JOIN) != 0;
        if (type == MediaFrame.Type.DESCRIPTOR) {
            return MediaFrame.descriptor(ptsUs, configEpoch, payload);
        }
        return MediaFrame.accessUnit(ptsUs, dtsUs, configEpoch, config, syncPoint, joinPoint,
                joinParts, payload);
    }

    private static MediaFrame.Type requireType(int typeCode, int version) throws IOException {
        if (version != VERSION) {
            throw new IOException("unsupported frame version: " + version);
        }
        MediaFrame.Type type = MediaFrame.Type.fromCode(typeCode);
        if (type == null) {
            throw new IOException("unsupported frame type: " + typeCode);
        }
        return type;
    }

    private static List<CodecConfigPart> readJoinParts(ByteBuffer in) throws IOException {
        require(in, 2);
        int count = in.getShort() & 0xffff;
        List<CodecConfigPart> parts = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            require(in, 2);
            int idLength = in.getShort() & 0xffff;
            require(in, idLength + 4);
            byte[] id = new byte[idLength];
            in.get(id);
            int payloadLength = in.getInt();
            if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_BYTES) {
                throw new IOException("join part payload length out of range");
            }
            require(in, payloadLength);
            byte[] payload = new byte[payloadLength];
            in.get(payload);
            parts.add(new CodecConfigPart(new String(id, StandardCharsets.UTF_8), payload));
        }
        return parts;
    }

    private static CodecConfigPart readJoinPart(InputStream in) throws IOException {
        byte[] idLength = new byte[2];
        readExact(in, idLength);
        int length = ByteBuffer.wrap(idLength).getShort() & 0xffff;
        byte[] id = new byte[length];
        readExact(in, id);
        byte[] payloadLength = new byte[4];
        readExact(in, payloadLength);
        int payloadBytes = ByteBuffer.wrap(payloadLength).getInt();
        if (payloadBytes < 0 || payloadBytes > MAX_PAYLOAD_BYTES) {
            throw new IOException("join part payload length out of range");
        }
        byte[] payload = new byte[payloadBytes];
        readExact(in, payload);
        return new CodecConfigPart(new String(id, StandardCharsets.UTF_8), payload);
    }

    private static void readExact(InputStream in, byte[] target) throws IOException {
        int offset = 0;
        while (offset < target.length) {
            int read = in.read(target, offset, target.length - offset);
            if (read < 0) {
                throw new EOFException("stream ended inside a frame");
            }
            offset += read;
        }
    }

    private static void require(ByteBuffer buffer, int bytes) throws IOException {
        if (buffer.remaining() < bytes) {
            throw new EOFException("frame body truncated");
        }
    }

    private static int joinPartsBytes(List<CodecConfigPart> joinParts) {
        if (joinParts.isEmpty()) {
            return 0;
        }
        int bytes = 2;
        for (CodecConfigPart part : joinParts) {
            byte[] id = part.getId() == null
                    ? new byte[0] : part.getId().getBytes(StandardCharsets.UTF_8);
            byte[] payload = part.getPayload() == null ? new byte[0] : part.getPayload();
            bytes += 2 + id.length + 4 + payload.length;
        }
        return bytes;
    }
}
