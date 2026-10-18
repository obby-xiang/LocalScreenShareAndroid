package com.obby.android.localscreenshare.core.support;

/**
 * MSB-first bit writer used to synthesize H.264 RBSP byte sequences in tests.
 * The writer never performs emulation prevention; fixtures apply that byte-level
 * transform explicitly when they need it.
 */
public final class H264BitWriter {

    private byte[] buffer = new byte[32];
    private int bitLength;

    public H264BitWriter writeBit(int bit) {
        if (bit != 0 && bit != 1) {
            throw new IllegalArgumentException("bit must be 0 or 1: " + bit);
        }
        ensureCapacity(1);
        if (bit != 0) {
            buffer[bitLength >>> 3] |= (byte) (0x80 >>> (bitLength & 7));
        }
        bitLength++;
        return this;
    }

    public H264BitWriter writeBits(int value, int bitCount) {
        if (bitCount < 0 || bitCount > 32) {
            throw new IllegalArgumentException("bitCount out of range: " + bitCount);
        }
        for (int i = bitCount - 1; i >= 0; i--) {
            writeBit((value >>> i) & 1);
        }
        return this;
    }

    public H264BitWriter writeBytes(byte[] bytes) {
        if ((bitLength & 7) != 0) {
            throw new IllegalStateException("byte writes require byte alignment");
        }
        ensureCapacity(bytes.length * 8);
        System.arraycopy(bytes, 0, buffer, bitLength >>> 3, bytes.length);
        bitLength += bytes.length * 8;
        return this;
    }

    public H264BitWriter writeUnsignedExpGolomb(int value) {
        if (value < 0 || value == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("value out of range: " + value);
        }
        int code = value + 1;
        int codeBits = 32 - Integer.numberOfLeadingZeros(code);
        for (int i = 0; i < codeBits - 1; i++) {
            writeBit(0);
        }
        return writeBits(code, codeBits);
    }

    public H264BitWriter writeSignedExpGolomb(int value) {
        int code = value <= 0 ? -2 * value : 2 * value - 1;
        return writeUnsignedExpGolomb(code);
    }

    public H264BitWriter writeRbspTrailingBits() {
        writeBit(1);
        while ((bitLength & 7) != 0) {
            writeBit(0);
        }
        return this;
    }

    public int bitLength() {
        return bitLength;
    }

    public byte[] toByteArray() {
        int byteLength = (bitLength + 7) >>> 3;
        byte[] result = new byte[byteLength];
        System.arraycopy(buffer, 0, result, 0, byteLength);
        return result;
    }

    private void ensureCapacity(int additionalBits) {
        int requiredBytes = (bitLength + additionalBits + 7) >>> 3;
        if (requiredBytes <= buffer.length) {
            return;
        }
        int grownLength = Math.max(requiredBytes, buffer.length * 2);
        byte[] grown = new byte[grownLength];
        System.arraycopy(buffer, 0, grown, 0, buffer.length);
        buffer = grown;
    }
}
