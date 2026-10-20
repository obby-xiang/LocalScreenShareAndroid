package com.obby.android.localscreenshare.core.codec;

/**
 * Well-known codec identifiers.
 *
 * <p>These ids are the registry keys and the tokens exchanged in client capabilities. Only
 * {@link #JPEG} is registered by default; the other constants are reserved for adapters that are
 * registered explicitly.</p>
 */
public final class CodecIds {

    /**
     * Reference intra-frame codec; every complete frame is independently decodable.
     */
    public static final String JPEG = "jpeg";

    /**
     * H.264/AVC, a reference-dependent codec that publishes configuration parts.
     */
    public static final String H264 = "h264";

    /**
     * Reserved identifier; no adapter registers it in this batch.
     */
    public static final String HEVC = "hevc";

    /**
     * Reserved identifier; no adapter registers it in this batch.
     */
    public static final String AV1 = "av1";

    private CodecIds() {
    }
}
