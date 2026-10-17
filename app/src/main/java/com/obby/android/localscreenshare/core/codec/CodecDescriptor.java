package com.obby.android.localscreenshare.core.codec;

import com.obby.android.localscreenshare.core.model.BitstreamFormat;

/**
 * Immutable, static description of a codec's capabilities and media type.
 */
public final class CodecDescriptor {

    private final String codecId;
    private final String mime;
    private final BitstreamFormat bitstreamFormat;
    private final boolean surfaceInputRequired;
    private final boolean surfaceOutputRequired;
    private final boolean carriesConfigParts;

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

    public boolean isSurfaceInputRequired() {
        return surfaceInputRequired;
    }

    public boolean isSurfaceOutputRequired() {
        return surfaceOutputRequired;
    }

    public boolean hasConfigParts() {
        return carriesConfigParts;
    }
}
