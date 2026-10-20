package com.obby.android.localscreenshare.core.model;

/**
 * Framing of the encoded bitstream carried by a stream.
 *
 * <p>The format is part of the decode configuration and therefore part of the stream descriptor's
 * config hash; changing it requires a new config epoch.</p>
 */
public enum BitstreamFormat {
    /**
     * Each access unit is one complete, independently decodable image.
     */
    JPEG_FRAME,

    /**
     * NAL units separated by start codes; parameter sets travel as descriptor config parts.
     */
    ANNEX_B,

    /**
     * Length-prefixed NAL units, as used by container-style framings.
     */
    AVCC
}
