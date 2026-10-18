package com.obby.android.localscreenshare.core.codec.h264;

import java.util.Arrays;

/**
 * MSB-first bit reader over an H.264 RBSP. Emulation prevention bytes
 * ({@code 00 00 03}) are removed transparently before any bit is read.
 */
public final class H264BitReader {

    private final byte[] rbsp;
    private int bitPosition;

    public H264BitReader(byte[] ebsp) {
        this.rbsp = removeEmulationPrevention(ebsp);
    }

    public int readBit() {
        if (bitPosition >= rbsp.length * 8) {
            throw new H264FormatException("read past end of RBSP");
        }
        int bit = (rbsp[bitPosition >>> 3] >>> (7 - (bitPosition & 7))) & 1;
        bitPosition++;
        return bit;
    }

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
