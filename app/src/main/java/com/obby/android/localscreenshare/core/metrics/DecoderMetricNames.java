package com.obby.android.localscreenshare.core.metrics;

/**
 * Metric keys recorded when the viewer configures its decoder.
 *
 * <p>The key is kept in its own holder because the client pipeline holder is already over the
 * size limit of the naming conventions, and because the value describes the decoder that was
 * built rather than a per-frame reading. It is a gauge with three reported values: zero means the
 * platform was too old to ask, one means the low-latency request took effect (including the case
 * where only the priority hint was dropped), and two means every attempt was rejected and the
 * codec fell back. Zero is also the "never measured" value - and the value the decoder writes back
 * when it is closed - so the formatter never prints it.</p>
 */
public final class DecoderMetricNames {

    /**
     * Low-latency state of the configured decoder: 1 = applied, 2 = requested then rejected.
     */
    public static final String DEC_LOW_LATENCY = "dec.lowLat";

    private DecoderMetricNames() {
    }
}
