package com.obby.android.localscreenshare.core.codec;

import com.obby.android.localscreenshare.core.model.CodecConfigPart;
import com.obby.android.localscreenshare.core.model.ColorInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable configuration reported by an encoder after it has started.
 *
 * <p>This is the second half of deferred configuration publication. A codec whose descriptor
 * carries configuration parts cannot know its final coded size or parameter sets before its codec
 * pipeline runs, so the encoder reports them through {@link EncoderOutput#onEncoderConfig} before
 * the first access unit. The runtime combines this snapshot with the requested descriptor (stream
 * identity, config epoch, visible size, rotation, profile and level) to build the published stream
 * descriptor.</p>
 *
 * <p><b>Source constraints.</b> The coded size and color must describe the encoder's actual output
 * format, never the requested descriptor: alignment, cropping and device-specific scaling make the
 * requested coded size unreliable. {@code configParts} must contain everything a decoder needs to
 * start; an empty list is not a valid configuration and is rejected rather than published.
 * Profile and level are not carried here, so the runtime keeps the requested values.</p>
 *
 * <p>The instance is immutable and the configuration parts are held in an unmodifiable copy.
 * Changing any value requires a new encoder and a new config epoch.</p>
 */
public final class EncoderConfig {

    private final int codedWidth;
    private final int codedHeight;
    private final ColorInfo colorInfo;
    private final List<CodecConfigPart> configParts;

    /**
     * Creates a configuration snapshot.
     *
     * @param codedWidth  actual coded width reported by the encoder; must be positive
     * @param codedHeight actual coded height reported by the encoder; must be positive
     * @param colorInfo   resolved color description; use {@link ColorInfo#UNSPECIFIED} when unknown
     * @param configParts codec configuration a decoder needs; must be non-null and non-empty
     * @throws IllegalArgumentException if a size is non-positive, a reference is null, or the
     *                                  configuration part list is empty
     */
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
