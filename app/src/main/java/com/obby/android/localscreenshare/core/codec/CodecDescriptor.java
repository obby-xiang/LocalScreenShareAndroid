package com.obby.android.localscreenshare.core.codec;

import com.obby.android.localscreenshare.core.model.BitstreamFormat;

/**
 * Immutable, static description of a codec's identity, media type and platform requirements.
 *
 * <p>This is registration-time metadata, not the per-stream decode configuration; the latter is
 * the stream descriptor. The three boolean capabilities tell the runtime which resources a codec
 * needs:</p>
 * <ul>
 *   <li>{@code surfaceInputRequired}: an encoder must expose a non-null input surface, so the
 *       runtime validates {@link Encoder#getInputSurface()} before starting it.</li>
 *   <li>{@code surfaceOutputRequired}: a decoder renders directly into a surface, so a client
 *       session needs a non-null surface from the render target before it may configure a
 *       decoder.</li>
 *   <li>{@code carriesConfigParts}: the encoder produces its decode configuration after start and
 *       reports it through {@link EncoderOutput#onEncoderConfig}, so the stream descriptor is not
 *       published until that configuration arrives.</li>
 * </ul>
 */
public final class CodecDescriptor {

    private final String codecId;
    private final String mime;
    private final BitstreamFormat bitstreamFormat;
    private final boolean surfaceInputRequired;
    private final boolean surfaceOutputRequired;
    private final boolean carriesConfigParts;

    /**
     * Creates a descriptor. The identity fields are required and the codec id and MIME type must
     * not be blank.
     */
    public CodecDescriptor(String codecId, String mime, BitstreamFormat bitstreamFormat,
                           boolean surfaceInputRequired, boolean surfaceOutputRequired,
                           boolean carriesConfigParts) {
        if (codecId == null || codecId.trim().isEmpty()) {
            throw new IllegalArgumentException("codecId must not be null or blank");
        }
        if (mime == null || mime.trim().isEmpty()) {
            throw new IllegalArgumentException("mime must not be null or blank");
        }
        if (bitstreamFormat == null) {
            throw new IllegalArgumentException("bitstreamFormat must not be null");
        }
        this.codecId = codecId;
        this.mime = mime;
        this.bitstreamFormat = bitstreamFormat;
        this.surfaceInputRequired = surfaceInputRequired;
        this.surfaceOutputRequired = surfaceOutputRequired;
        this.carriesConfigParts = carriesConfigParts;
    }

    public String getCodecId() {
        return codecId;
    }

    public String getMime() {
        return mime;
    }

    public BitstreamFormat getBitstreamFormat() {
        return bitstreamFormat;
    }

    /** True when the encoder consumes input through a surface. */
    public boolean isSurfaceInputRequired() {
        return surfaceInputRequired;
    }

    /** True when the decoder renders into a surface and needs a non-null render-target surface. */
    public boolean isSurfaceOutputRequired() {
        return surfaceOutputRequired;
    }

    /** True when the decode configuration is produced by the encoder after start. */
    public boolean hasConfigParts() {
        return carriesConfigParts;
    }
}
