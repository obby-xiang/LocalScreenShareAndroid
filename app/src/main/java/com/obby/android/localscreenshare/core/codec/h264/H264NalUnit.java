package com.obby.android.localscreenshare.core.codec.h264;

import java.util.Arrays;

/**
 * A single H.264 Network Abstraction Layer unit as it appears in a byte
 * stream: one header byte followed by the raw RBSP payload (emulation
 * prevention bytes preserved, no start code).
 */
public final class H264NalUnit {

    private final byte[] bytes;
    private final int header;

    public H264NalUnit(byte[] nalBytes) {
        if (nalBytes == null || nalBytes.length < 1) {
            throw new IllegalArgumentException("NAL unit must contain at least a header byte");
        }
        this.bytes = nalBytes.clone();
        this.header = this.bytes[0] & 0xFF;
    }

    /**
     * NAL unit type: the low 5 bits of the header byte (1..31).
     */
    public int getType() {
        return header & 0x1F;
    }

    /**
     * nal_ref_idc: bits 5-6 of the header byte.
     */
    public int getRefIdc() {
        return (header >>> 5) & 0x03;
    }

    /**
     * True for VCL NAL units (types 1..5).
     */
    public boolean isVcl() {
        int type = getType();
        return type >= 1 && type <= 5;
    }

    /**
     * Raw NAL bytes: header + RBSP, without start code, emulation prevention preserved.
     */
    public byte[] getBytes() {
        return bytes.clone();
    }

    /**
     * RBSP bytes after the header, emulation prevention preserved.
     */
    byte[] getRbsp() {
        return Arrays.copyOfRange(bytes, 1, bytes.length);
    }
}
