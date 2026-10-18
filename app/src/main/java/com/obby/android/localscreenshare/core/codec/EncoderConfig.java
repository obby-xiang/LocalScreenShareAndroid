package com.obby.android.localscreenshare.core.codec;

import com.obby.android.localscreenshare.core.model.CodecConfigPart;
import com.obby.android.localscreenshare.core.model.ColorInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable configuration reported by an encoder after it has started.
 *
 * <p>Carries the resolved coded size, the codec configuration fragments (e.g.
 * SPS/PPS CSD entries) and the color description. Profile and level are not
 * carried here; the runtime keeps them from the requested descriptor.</p>
 */
public final class EncoderConfig {

    private final int codedWidth;
    private final int codedHeight;
    private final ColorInfo colorInfo;
    private final List<CodecConfigPart> configParts;

    public EncoderConfig(int codedWidth, int codedHeight, ColorInfo colorInfo,
                         List<CodecConfigPart> configParts) {
        if (codedWidth <= 0) {
            throw new IllegalArgumentException("codedWidth must be positive");
        }
        if (codedHeight <= 0) {
            throw new IllegalArgumentException("codedHeight must be positive");
        }
        if (colorInfo == null) {
            throw new IllegalArgumentException("colorInfo must not be null");
        }
        if (configParts == null || configParts.isEmpty()) {
            throw new IllegalArgumentException("configParts must not be null or empty");
        }
        this.codedWidth = codedWidth;
        this.codedHeight = codedHeight;
        this.colorInfo = colorInfo;
        this.configParts = Collections.unmodifiableList(
                new ArrayList<CodecConfigPart>(configParts));
    }

    public int getCodedWidth() {
        return codedWidth;
    }

    public int getCodedHeight() {
        return codedHeight;
    }

    public ColorInfo getColorInfo() {
        return colorInfo;
    }

    public List<CodecConfigPart> getConfigParts() {
        return configParts;
    }
}
