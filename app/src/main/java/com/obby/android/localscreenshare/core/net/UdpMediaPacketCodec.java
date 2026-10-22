package com.obby.android.localscreenshare.core.net;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Wire format of the datagram media channel: one fragment of one access unit per datagram.
 *
 * <p><b>Layout.</b> Eight bytes of plaintext sequence number, then the authenticated body and its
 * tag. The sequence number stays in the clear because it is the nonce suffix: the receiver has to
 * know it to derive the nonce, and it is authenticated as additional data so it cannot be changed
 * without the tag failing.</p>
 *
 * <pre>
 * plaintext prefix (8 B)
 * 0   8  wireSeq
 * sealed body (AES-256-GCM, AAD = the 8 bytes of wireSeq)
 * 0   1  v/flags: bits7..6 version | bit5 CONFIG | bit4 SYNC | bit3 JOIN
 *                 bit2 PARITY | bit1 HAS_JOIN | bit0 reserved
 * 1   1  fragmentIndex
 * 2   2  fragmentCount
 * 4   4  auId
 * 8   8  ptsUs
 * 16  4  auSize
 * 20  2  configEpoch
 * 22  2  reserved (reserved for forward error correction)
 * 24  4  fragmentOffset
 * 28  .. fragment bytes
 * trailing 16 B authentication tag
 * </pre>
 *
 * <p><b>Bounds.</b> A datagram never exceeds {@value #MAX_DATAGRAM_BYTES} bytes, so a fragment
 * carries at most {@value #MAX_FRAGMENT_BYTES} bytes; an access unit never exceeds
 * {@value #MAX_AU_BYTES} bytes, so the fragment count never exceeds
 * {@value #MAX_FRAGMENT_COUNT}. Fragments are fixed size and their offset is derived from the
 * index, which means the receiver can validate both without trusting the sender: a datagram that
 * claims a different layout is rejected instead of being written somewhere it does not belong.</p>
 *
 * <p><b>Join configuration.</b> Version one does not carry the configuration parts of a join
 * point: every fragment is fixed size, and a variable-size section in front of the first fragment
 * would either overflow the datagram or make the fragment offsets depend on data the receiver may
 * not have seen yet. The parts are not needed for the join decision - the descriptor already
 * delivered them on the control plane, and an H.264 join point carries its own parameter sets in
 * band - so the receiver rebuilds the decision from the JOIN flag and the payload, exactly like the
 * stream channel does. The HAS_JOIN bit stays reserved for a future side channel; a datagram that
 * sets it in version one is rejected instead of being misread.</p>
 *
 * <p>Every malformed datagram throws an {@link IOException}; a failed authentication throws
 * {@link DatagramCipher.AuthenticationException}, so a receiver can count authentication failures
 * separately from structural ones.</p>
 */
public final class UdpMediaPacketCodec {

    /**
     * Largest datagram the channel sends, in bytes.
     */
    public static final int MAX_DATAGRAM_BYTES = 1400;

    /**
     * Length of the plaintext sequence prefix, in bytes.
     */
    public static final int SEQUENCE_BYTES = 8;

    /**
     * Length of the authenticated header, in bytes.
     */
    public static final int HEADER_BYTES = 28;

    /**
     * Length of the authentication tag, in bytes.
     */
    public static final int TAG_BYTES = DatagramCipher.TAG_BYTES;

    /**
     * Largest access-unit payload, in bytes.
     */
    public static final int MAX_AU_BYTES = 4 * 1024 * 1024;

    /**
     * Largest number of fragments of one access unit.
     */
    public static final int MAX_FRAGMENT_COUNT = 3112;

    /**
     * Largest fragment payload, in bytes.
     */
    public static final int MAX_FRAGMENT_BYTES =
            MAX_DATAGRAM_BYTES - SEQUENCE_BYTES - HEADER_BYTES - TAG_BYTES;

    /**
     * Version of the datagram format this codec writes and reads.
     */
    public static final int VERSION = 1;

    /**
     * The version bits of the flag byte, as they appear on the wire.
     */
    public static final int VERSION_FLAGS = 1 << 6;

    private static final int FLAG_VERSION = VERSION_FLAGS;

    private static final int FLAG_CONFIG = 1 << 5;

    private static final int FLAG_SYNC = 1 << 4;

    private static final int FLAG_JOIN = 1 << 3;

    private static final int FLAG_PARITY = 1 << 2;

    private static final int FLAG_HAS_JOIN = 1 << 1;

    private UdpMediaPacketCodec() {
    }

    /**
     * Returns how many fragments an access unit of {@code auSize} bytes needs.
     *
     * @param auSize payload size in bytes; must be within the format bound
     * @throws IllegalArgumentException if the size is out of range
     */
    public static int fragmentCount(int auSize) {
        if (auSize < 0 || auSize > MAX_AU_BYTES) {
            throw new IllegalArgumentException("auSize out of range: " + auSize);
        }
        return auSize == 0 ? 1 : (auSize + MAX_FRAGMENT_BYTES - 1) / MAX_FRAGMENT_BYTES;
    }

    /**
     * Encodes one fragment of an access unit into a datagram.
     *
     * @param frame          access unit to fragment; a descriptor has no datagram form
     * @param auId           identifier of this unit, chosen by the sender
     * @param fragmentIndex  index of the fragment to encode, from zero
     * @param wireSeq        sequence number of this datagram, unique per key
     * @param cipher         cipher of the sending direction; required
     * @return the datagram, sequence prefix included
     * @throws IllegalArgumentException if the frame is not an access unit or an index is out of
     *                                  range
     */
    public static byte[] encodeFragment(MediaFrame frame, int auId, int fragmentIndex,
                                        long wireSeq, DatagramCipher cipher) {
        if (frame == null) {
            throw new IllegalArgumentException("frame must not be null");
        }
        if (cipher == null) {
            throw new IllegalArgumentException("cipher must not be null");
        }
        if (frame.getType() != MediaFrame.Type.ACCESS_UNIT) {
            throw new IllegalArgumentException("only access units travel on datagrams");
        }
        byte[] payload = frame.getPayload();
        int auSize = payload.length;
        int count = fragmentCount(auSize);
        if (fragmentIndex < 0 || fragmentIndex >= count) {
            throw new IllegalArgumentException(
                    "fragmentIndex " + fragmentIndex + " out of range for " + count);
        }
        int offset = offsetOf(fragmentIndex);
        int length = lengthOf(auSize, fragmentIndex);

        int flags = FLAG_VERSION;
        if (frame.isConfig()) {
            flags |= FLAG_CONFIG;
        }
        if (frame.isSyncPoint()) {
            flags |= FLAG_SYNC;
        }
        if (frame.isJoinPoint()) {
            flags |= FLAG_JOIN;
        }
        ByteBuffer body = ByteBuffer.allocate(HEADER_BYTES + length);
        body.put((byte) flags);
        body.put((byte) fragmentIndex);
        body.putShort((short) count);
        body.putInt(auId);
        body.putLong(frame.getPtsUs());
        body.putInt(auSize);
        body.putShort((short) frame.getConfigEpoch());
        body.putShort((short) 0);
        body.putInt(offset);
        body.put(payload, offset, length);
        byte[] ciphertext = cipher.encrypt(wireSeq, body.array());
        ByteBuffer datagram = ByteBuffer.allocate(SEQUENCE_BYTES + ciphertext.length);
        datagram.putLong(wireSeq);
        datagram.put(ciphertext);
        byte[] bytes = datagram.array();
        if (bytes.length > MAX_DATAGRAM_BYTES) {
            throw new IllegalArgumentException("datagram exceeds " + MAX_DATAGRAM_BYTES + " bytes");
        }
        return bytes;
    }

    /**
     * Decodes and authenticates one datagram.
     *
     * @param datagram received bytes; required
     * @param cipher   cipher of the receiving direction; required
     * @return the parsed fragment
     * @throws DatagramCipher.AuthenticationException if the datagram did not authenticate
     * @throws IOException                            if the layout is malformed
     */
    public static UdpMediaPacket decode(byte[] datagram, DatagramCipher cipher)
            throws IOException {
        if (datagram == null) {
            throw new IllegalArgumentException("datagram must not be null");
        }
        if (cipher == null) {
            throw new IllegalArgumentException("cipher must not be null");
        }
        if (datagram.length < SEQUENCE_BYTES + HEADER_BYTES + TAG_BYTES) {
            throw new EOFException("datagram is shorter than the format");
        }
        ByteBuffer prefix = ByteBuffer.wrap(datagram, 0, SEQUENCE_BYTES);
        long wireSeq = prefix.getLong();
        byte[] ciphertext = new byte[datagram.length - SEQUENCE_BYTES];
        System.arraycopy(datagram, SEQUENCE_BYTES, ciphertext, 0, ciphertext.length);
        byte[] plaintext = cipher.decrypt(wireSeq, ciphertext);
        if (plaintext.length < HEADER_BYTES) {
            throw new IOException("datagram body is shorter than its header");
        }
        ByteBuffer body = ByteBuffer.wrap(plaintext);
        int flags = body.get() & 0xff;
        int fragmentIndex = body.get() & 0xff;
        int fragmentCount = body.getShort() & 0xffff;
        int auId = body.getInt();
        long ptsUs = body.getLong();
        int auSize = body.getInt();
        int configEpoch = body.getShort() & 0xffff;
        body.getShort();
        int fragmentOffset = body.getInt();

        if ((flags & 0xc0) != FLAG_VERSION) {
            throw new IOException("unsupported datagram version");
        }
        if (fragmentCount < 1 || fragmentCount > MAX_FRAGMENT_COUNT) {
            throw new IOException("fragment count out of range: " + fragmentCount);
        }
        if (fragmentIndex >= fragmentCount) {
            throw new IOException("fragment index " + fragmentIndex + " of " + fragmentCount);
        }
        int expectedCount = fragmentCount(auSize);
        if (expectedCount != fragmentCount) {
            throw new IOException("fragment count " + fragmentCount + " does not match size "
                    + auSize);
        }
        if (fragmentOffset != offsetOf(fragmentIndex)) {
            throw new IOException("unexpected fragment offset " + fragmentOffset);
        }
        if ((flags & FLAG_HAS_JOIN) != 0) {
            throw new IOException("join configuration is not part of this format version");
        }
        int expectedLength = lengthOf(auSize, fragmentIndex);
        int available = plaintext.length - body.position();
        if (available != expectedLength) {
            throw new IOException("fragment has " + available + " bytes, expected "
                    + expectedLength);
        }
        byte[] fragment = new byte[expectedLength];
        body.get(fragment);
        return new UdpMediaPacket(wireSeq, auId, fragmentIndex, fragmentCount, ptsUs, auSize,
                configEpoch, (flags & FLAG_CONFIG) != 0, (flags & FLAG_SYNC) != 0,
                (flags & FLAG_JOIN) != 0, (flags & FLAG_PARITY) != 0, fragmentOffset, fragment,
                null);
    }

    /**
     * Returns the offset of a fragment inside the payload.
     */
    static int offsetOf(int fragmentIndex) {
        return fragmentIndex * MAX_FRAGMENT_BYTES;
    }

    /**
     * Returns the length of a fragment inside the payload.
     */
    static int lengthOf(int auSize, int fragmentIndex) {
        int offset = offsetOf(fragmentIndex);
        return Math.min(MAX_FRAGMENT_BYTES, auSize - offset);
    }
}
