package com.obby.android.localscreenshare.core.codec.h264;

import java.util.Arrays;

/**
 * MSB-first bit reader over an H.264 RBSP. Emulation prevention bytes
 * ({@code 00 00 03}) are removed transparently before any bit is read.
 *
 * <p><b>Emulation prevention.</b> H.264 escapes a payload byte sequence that would otherwise contain
 * {@code 00 00 00}, {@code 00 00 01}, {@code 00 00 02} or {@code 00 00 03} by inserting a {@code 03}
 * byte after two zero bytes. The constructor strips every such {@code 03} while it scans the escaped
 * bytes, so the reader works on the original RBSP and callers never pre-process. Detection looks at
 * the two previously emitted bytes, which is the inverse of the escaping rule.</p>
 *
 * <p><b>Bit order.</b> Bits are consumed most-significant-bit first within each byte, matching the
 * bitstream order of the RBSP.</p>
 *
 * <p><b>Bounds.</b> Reading past the end throws {@link H264FormatException} instead of returning
 * zero bits, so a truncated slice header cannot be mistaken for a valid one. Instances are mutable
 * and not thread-safe; create one per parse.</p>
 */
public final class H264BitReader {

    private final byte[] rbsp;
    private int bitPosition;

    /**
     * Creates a reader over the given escaped payload. The input array is neither modified nor
     * retained.
     *
     * @param ebsp NAL payload without its header byte; {@code null} or empty yields a reader with no
     *             bits, which throws on the first read
     */
    public H264BitReader(byte[] ebsp) {
        this.rbsp = removeEmulationPrevention(ebsp);
    }

    /**
     * Reads one bit, most significant bit first.
     *
     * @return {@code 0} or {@code 1}
     * @throws H264FormatException when no bits remain
     */
    public int readBit() {
        if (bitPosition >= rbsp.length * 8) {
            throw new H264FormatException("read past end of RBSP");
        }
        int bit = (rbsp[bitPosition >>> 3] >>> (7 - (bitPosition & 7))) & 1;
        bitPosition++;
        return bit;
    }

    /**
     * Reads an unsigned Exp-Golomb code ({@code ue(v)}).
     *
     * <p>The code is a run of {@code k} zero bits, a one bit, then {@code k} suffix bits, with the
     * value {@code (1 << k) - 1 + suffix}. A leading-zero run longer than 30 bits is rejected before
     * any shift is applied, so a corrupt stream cannot produce a negative or overflowing value.</p>
     *
     * @return the decoded value, always non-negative
     * @throws H264FormatException when the code is truncated or its leading-zero run exceeds 30 bits
     */
    public int readUnsignedExpGolomb() {
        int leadingZeroBits = 0;
        while (readBit() == 0) {
            leadingZeroBits++;
            if (leadingZeroBits > 30) {
                throw new H264FormatException("unsigned Exp-Golomb code too large");
            }
        }
        int suffix = 0;
        for (int i = 0; i < leadingZeroBits; i++) {
            suffix = (suffix << 1) | readBit();
        }
        return (1 << leadingZeroBits) - 1 + suffix;
    }

    /**
     * Copies {@code ebsp} into a new array, dropping each {@code 03} that follows two zero bytes.
     * The two-zero counter tracks emitted bytes, not input bytes, so consecutive escapes are handled.
     */
    private static byte[] removeEmulationPrevention(byte[] ebsp) {
        if (ebsp == null || ebsp.length == 0) {
            return new byte[0];
        }
        byte[] out = new byte[ebsp.length];
        int outLen = 0;
        int zeroCount = 0;
        for (int i = 0; i < ebsp.length; i++) {
            int value = ebsp[i] & 0xFF;
            if (zeroCount >= 2 && value == 0x03) {
                zeroCount = 0;
                continue;
            }
            out[outLen++] = (byte) value;
            zeroCount = value == 0 ? zeroCount + 1 : 0;
        }
        return Arrays.copyOf(out, outLen);
    }
}
