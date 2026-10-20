package com.obby.android.localscreenshare.core.support;

import java.util.Base64;

/**
 * JVM-only JPEG byte fixtures. No fixture is produced or inspected through
 * Android graphics APIs or {@code ImageIO}.
 */
public final class JpegFixtures {

    private static final String REAL_JPEG_1X1_BASE64 =
            "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRof"
                    + "Hh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAAB"
                    + "AAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q==";

    /**
     * A real, decodable 160-byte baseline JPEG (1x1 pixel, single component).
     * Its SOF0 segment declares 1x1 and Windows GDI+ decodes it to 1x1.
     */
    public static final byte[] REAL_JPEG = Base64.getDecoder().decode(REAL_JPEG_1X1_BASE64);

    /**
     * Smallest marker-only frame: SOI immediately followed by EOI.
     */
    public static final byte[] VALID_FRAME = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9};

    public static final byte[] TRUNCATED_FRAME = {(byte) 0xFF, (byte) 0xD8, 0x00};

    public static final byte[] EMPTY_FRAME = new byte[0];

    /**
     * {@link #REAL_JPEG} with an empty COM segment inserted after the SOI marker.
     * Still a complete JPEG frame, but not the canonical fixture bytes.
     */
    public static byte[] validFrameWithPadding() {
        byte[] padded = new byte[REAL_JPEG.length + 4];
        padded[0] = (byte) 0xFF;
        padded[1] = (byte) 0xD8;
        padded[2] = (byte) 0xFF;
        padded[3] = (byte) 0xFE;
        padded[4] = 0x00;
        padded[5] = 0x02;
        System.arraycopy(REAL_JPEG, 2, padded, 6, REAL_JPEG.length - 2);
        return padded;
    }

    /**
     * A complete frame followed by bytes after EOI, so it does not end with EOI.
     */
    public static byte[] frameWithTrailingGarbage() {
        byte[] frame = new byte[REAL_JPEG.length + 2];
        System.arraycopy(REAL_JPEG, 0, frame, 0, REAL_JPEG.length);
        frame[REAL_JPEG.length] = 0x00;
        frame[REAL_JPEG.length + 1] = 0x01;
        return frame;
    }

    private JpegFixtures() {
    }
}
